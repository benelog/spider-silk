import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists the dependencies this repository declares that have a newer release, and with {@code --apply} moves them.
 *
 * <pre>
 * java scripts/DependencyUpdates.java                  # report only
 * java scripts/DependencyUpdates.java --apply          # every update within the current major
 * java scripts/DependencyUpdates.java --apply jetty    # only the names containing "jetty", to the latest, major or not
 * </pre>
 *
 * It reads every file git tracks: the coordinates and plugin ids in the Gradle builds (benchmark/ and the
 * included plugin build too), the Maven parent's pom.xml, package.json, the GitHub Actions workflows, and the
 * Gradle wrapper, plus the copies of a version that live outside a build file ({@link #MIRRORS}).
 * A candidate release counts only when its qualifier matches the current one, so 3.1.5.RELEASE moves to
 * 3.1.6.RELEASE but never to 3.2.0.M1, and 2.0.65 never to 2.0.66.android8.
 * Base images are listed but not looked up: their tags name a line, not a release.
 *
 * Nothing is committed and nothing is built; the dependency-update skill (.claude/skills/) does the rest.
 * Run it from the repository root with JDK 25, the one the build already needs.
 */
public class DependencyUpdates {

    enum Source { MAVEN, GRADLE_PLUGIN, NPM, GITHUB_ACTION, GRADLE }

    record Dependency(Source source, String name) {}

    /** One place a version is written: {@code start} and {@code end} delimit the version in the file's text. */
    record Declaration(Dependency dependency, Path file, int line, int start, int end, String version) {}

    record Mirror(String file, Pattern pattern, Dependency dependency) {}

    /**
     * Versions written somewhere no scanner reads, each kept in step with a dependency by hand until now.
     * The pattern's first group is the version.
     */
    static final List<Mirror> MIRRORS = List.of(
            mirror("spider-silk-gradle-plugin/src/main/java/net/benelog/spidersilk/gradle/SpiderSilkExtension.java",
                    "JTE_VERSION = \"([^\"]+)\"", Source.MAVEN, "gg.jte:jte"),
            mirror("spider-silk-gradle-plugin/src/main/java/net/benelog/spidersilk/gradle/SpiderSilkExtension.java",
                    "JSONB_API_VERSION = \"([^\"]+)\"", Source.MAVEN, "jakarta.json.bind:jakarta.json.bind-api"),
            mirror("manual/antora.yml", "jte-version: (\\S+)", Source.MAVEN, "gg.jte:jte"),
            mirror("manual/antora.yml", "jib-version: (\\S+)", Source.GRADLE_PLUGIN, "com.google.cloud.tools.jib"),
            mirror("manual/antora.yml", "jib-maven-version: (\\S+)", Source.MAVEN, "com.google.cloud.tools:jib-maven-plugin"),
            mirror("manual/antora.yml", "graalvm-buildtools-version: (\\S+)", Source.GRADLE_PLUGIN, "org.graalvm.buildtools.native"),
            mirror("manual/antora.yml", "otel-agent-version: (\\S+)", Source.MAVEN, "io.opentelemetry.javaagent:opentelemetry-javaagent"),
            mirror("manual-ko/antora.yml", "jte-version: (\\S+)", Source.MAVEN, "gg.jte:jte"),
            mirror("manual-ko/antora.yml", "jib-version: (\\S+)", Source.GRADLE_PLUGIN, "com.google.cloud.tools.jib"),
            mirror("manual-ko/antora.yml", "jib-maven-version: (\\S+)", Source.MAVEN, "com.google.cloud.tools:jib-maven-plugin"),
            mirror("manual-ko/antora.yml", "graalvm-buildtools-version: (\\S+)", Source.GRADLE_PLUGIN, "org.graalvm.buildtools.native"),
            mirror("manual-ko/antora.yml", "otel-agent-version: (\\S+)", Source.MAVEN, "io.opentelemetry.javaagent:opentelemetry-javaagent"));

    /** The repository's own artifacts, which a release moves, not this script. */
    static final String OWN_GROUP = "net.benelog.";

    static final String CENTRAL = "https://repo1.maven.org/maven2/";
    static final String PLUGIN_PORTAL = "https://plugins.gradle.org/m2/";

    static final Pattern GRADLE_COORDINATE = Pattern.compile("(['\"])([\\w.-]+):([\\w.-]+):(\\d[\\w.+-]*)\\1");
    static final Pattern GRADLE_PLUGIN = Pattern.compile("\\bid\\s*\\(?\\s*['\"]([\\w.-]+)['\"]\\s*\\)?\\s+version\\s+['\"](\\d[\\w.+-]*)['\"]");
    static final Pattern GRADLE_VARIABLE = Pattern.compile("\\bdef\\s+(\\w+)\\s*=\\s*['\"](\\d[\\w.+-]*)['\"]");
    static final Pattern POM_GAV = Pattern.compile("<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>\\s*<version>([^<]+)</version>");
    static final Pattern POM_PROPERTY = Pattern.compile("<([\\w.-]+)>(\\d[^<]*)</\\1>");
    static final Pattern NPM_BLOCK = Pattern.compile("\"(?:dependencies|devDependencies)\"\\s*:\\s*\\{([^}]*)}");
    static final Pattern NPM_ENTRY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"[~^]?(\\d[^\"]*)\"");
    static final Pattern ACTION = Pattern.compile("uses:\\s*([\\w.-]+/[\\w.-]+)(?:/[\\w./-]+)?@(v?\\d[\\w.]*)");
    static final Pattern WRAPPER = Pattern.compile("distributionUrl=.*?/gradle-(\\d[\\w.+-]*?)-(?:bin|all)\\.zip");
    static final Pattern DOCKER_FROM = Pattern.compile("(?m)^FROM\\s+(\\S+)");
    static final Pattern POM_IMAGE = Pattern.compile("<image>([^<$]+)</image>");
    static final Pattern JAVA_IMAGE = Pattern.compile("_IMAGE\\s*=\\s*\"([^\"]+)\"");

    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws IOException {
        boolean apply = false;
        List<String> only = new ArrayList<>();
        for (String arg : args) {
            switch (arg) {
                case "--apply" -> apply = true;
                case "-h", "--help" -> {
                    System.out.println("usage: java scripts/DependencyUpdates.java [--apply [name...]]");
                    return;
                }
                default -> {
                    if (arg.startsWith("-")) {
                        System.err.println("unknown option " + arg + "; usage: java scripts/DependencyUpdates.java [--apply [name...]]");
                        System.exit(2);
                    }
                    only.add(arg);
                }
            }
        }
        if (!only.isEmpty() && !apply) {
            System.err.println("names select what --apply moves; pass --apply with them");
            System.exit(2);
        }

        List<Path> files = trackedFiles();
        List<Declaration> declarations = new ArrayList<>();
        for (Path file : files) {
            declarations.addAll(scan(file));
        }

        Map<Dependency, CompletableFuture<List<Version>>> lookups = new LinkedHashMap<>();
        for (Declaration d : declarations) {
            lookups.computeIfAbsent(d.dependency(), DependencyUpdates::releases);
        }
        Map<Dependency, List<Version>> releases = new LinkedHashMap<>();
        lookups.forEach((dependency, lookup) -> releases.put(dependency, lookup.join()));

        report(declarations, releases);
        reportImages(files);

        if (apply) {
            Map<Declaration, String> moves = new LinkedHashMap<>();
            for (Declaration d : declarations) {
                boolean named = only.stream().anyMatch(name -> label(d.dependency()).contains(name));
                if (!only.isEmpty() && !named) {
                    continue;
                }
                Targets targets = targets(d.version(), releases.get(d.dependency()));
                String target = only.isEmpty() ? targets.sameMajor() : targets.latest();
                if (target != null) {
                    moves.put(d, target);
                }
            }
            applyMoves(moves);
            reportMentions(files, moves);
        }
    }

    // --- scanning ---

    static List<Path> trackedFiles() throws IOException {
        Process git = new ProcessBuilder("git", "ls-files").redirectErrorStream(true).start();
        String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return out.lines().map(Path::of).filter(Files::isRegularFile).toList();
    }

    static List<Declaration> scan(Path file) throws IOException {
        String name = file.toString().replace('\\', '/');
        List<Declaration> found = new ArrayList<>();
        boolean mirrored = MIRRORS.stream().anyMatch(m -> m.file().equals(name));
        boolean build = name.endsWith(".gradle") || name.endsWith("pom.xml") || name.endsWith("package.json")
                || name.endsWith("gradle-wrapper.properties")
                || (name.startsWith(".github/workflows/") && name.matches(".*\\.ya?ml"));
        if (!build && !mirrored) {
            return found;
        }
        String text = Files.readString(file);

        if (name.endsWith(".gradle")) {
            Matcher m = GRADLE_COORDINATE.matcher(text);
            while (m.find()) {
                if (!m.group(2).startsWith(OWN_GROUP)) {
                    found.add(declaration(Source.MAVEN, m.group(2) + ":" + m.group(3), file, text, m, 4));
                }
            }
            m = GRADLE_PLUGIN.matcher(text);
            while (m.find()) {
                if (!m.group(1).startsWith(OWN_GROUP)) {
                    found.add(declaration(Source.GRADLE_PLUGIN, m.group(1), file, text, m, 2));
                }
            }
            // def otelAgentVersion = '2.31.1', read back as "group:artifact:${otelAgentVersion}";
            // a use with a qualifier after the variable (${otelAgentVersion}-alpha) does not name its releases
            m = GRADLE_VARIABLE.matcher(text);
            while (m.find()) {
                Matcher use = Pattern.compile("['\"]([\\w.-]+):([\\w.-]+):\\$\\{?" + m.group(1) + "}?['\"]").matcher(text);
                if (use.find()) {
                    found.add(declaration(Source.MAVEN, use.group(1) + ":" + use.group(2), file, text, m, 2));
                }
            }
        } else if (name.endsWith("pom.xml")) {
            Map<String, String> properties = new TreeMap<>();
            Matcher m = POM_GAV.matcher(text);
            while (m.find()) {
                String coordinate = m.group(1) + ":" + m.group(2);
                String version = m.group(3).trim();
                if (m.group(1).startsWith(OWN_GROUP)) {
                    continue;
                }
                if (version.startsWith("${") && version.endsWith("}")) {
                    properties.put(version.substring(2, version.length() - 1), coordinate);
                } else if (Character.isDigit(version.charAt(0))) {
                    found.add(declaration(Source.MAVEN, coordinate, file, text, m, 3));
                }
            }
            m = POM_PROPERTY.matcher(text);
            while (m.find()) {
                String coordinate = properties.get(m.group(1));
                if (coordinate != null) {
                    found.add(declaration(Source.MAVEN, coordinate, file, text, m, 2));
                }
            }
        } else if (name.endsWith("package.json")) {
            Matcher block = NPM_BLOCK.matcher(text);
            while (block.find()) {
                Matcher m = NPM_ENTRY.matcher(text).region(block.start(1), block.end(1));
                while (m.find()) {
                    found.add(declaration(Source.NPM, m.group(1), file, text, m, 2));
                }
            }
        } else if (name.endsWith("gradle-wrapper.properties")) {
            Matcher m = WRAPPER.matcher(text);
            while (m.find()) {
                found.add(declaration(Source.GRADLE, "gradle", file, text, m, 1));
            }
        } else if (name.startsWith(".github/workflows/")) {
            Matcher m = ACTION.matcher(text);
            while (m.find()) {
                found.add(declaration(Source.GITHUB_ACTION, m.group(1), file, text, m, 2));
            }
        }

        for (Mirror mirror : MIRRORS) {
            if (!mirror.file().equals(name)) {
                continue;
            }
            Matcher m = mirror.pattern().matcher(text);
            if (m.find()) {
                found.add(declaration(mirror.dependency().source(), mirror.dependency().name(), file, text, m, 1));
            } else {
                System.err.println("warning: " + name + " no longer has " + mirror.pattern() + "; update MIRRORS");
            }
        }
        return found;
    }

    static Declaration declaration(Source source, String name, Path file, String text, Matcher m, int group) {
        int line = (int) text.substring(0, m.start(group)).chars().filter(c -> c == '\n').count() + 1;
        return new Declaration(new Dependency(source, name), file, line, m.start(group), m.end(group), m.group(group));
    }

    static Mirror mirror(String file, String pattern, Source source, String name) {
        return new Mirror(file, Pattern.compile(pattern), new Dependency(source, name));
    }

    // --- looking up releases ---

    static CompletableFuture<List<Version>> releases(Dependency dependency) {
        return switch (dependency.source()) {
            case MAVEN -> {
                String[] ga = dependency.name().split(":");
                yield mavenMetadata(ga[0], ga[1]);
            }
            case GRADLE_PLUGIN -> mavenMetadata(dependency.name(), dependency.name() + ".gradle.plugin");
            case NPM -> get("https://registry.npmjs.org/" + URLEncoder.encode(dependency.name(), StandardCharsets.UTF_8)
                            .replace("%40", "@"), "application/vnd.npm.install-v1+json")
                    .thenApply(body -> versions(body, "\"version\"\\s*:\\s*\"([^\"]+)\""));
            case GITHUB_ACTION -> get("https://api.github.com/repos/" + dependency.name() + "/releases?per_page=100",
                            "application/vnd.github+json")
                    .thenApply(body -> versions(body, "\"tag_name\"\\s*:\\s*\"([^\"]+)\""));
            case GRADLE -> get("https://services.gradle.org/versions/all", "application/json")
                    .thenApply(body -> versions(body, "\"version\"\\s*:\\s*\"([^\"]+)\""));
        };
    }

    /** Central and the Gradle Plugin Portal both, since plugin markers live on one or the other or both. */
    static CompletableFuture<List<Version>> mavenMetadata(String group, String artifact) {
        String path = group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
        return get(CENTRAL + path, "application/xml")
                .thenCombine(get(PLUGIN_PORTAL + path, "application/xml"), (central, portal) -> central + portal)
                .thenApply(body -> versions(body, "<version>([^<]+)</version>"));
    }

    static List<Version> versions(String body, String regex) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = Pattern.compile(regex).matcher(body);
        while (m.find()) {
            seen.add(m.group(1));
        }
        return seen.stream().map(Version::parse).filter(v -> v != null).toList();
    }

    /** The body, or "" for a 404 or a failure, so one missing repository never stops the run. */
    static CompletableFuture<String> get(String url, String accept) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", accept)
                .header("User-Agent", "spider-silk-dependency-updates");
        String token = System.getenv().getOrDefault("GITHUB_TOKEN", System.getenv("GH_TOKEN"));
        if (url.startsWith("https://api.github.com/") && token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return response.body();
                    }
                    if (response.statusCode() != 404) {
                        System.err.println("warning: " + url + " answered " + response.statusCode());
                    }
                    return "";
                })
                .exceptionally(e -> {
                    System.err.println("warning: " + url + " failed: " + e.getMessage());
                    return "";
                });
    }

    // --- versions ---

    /** A version is a numeric part with an optional "v" before it and a qualifier after it. */
    record Version(String text, String prefix, int[] numbers, String qualifier) implements Comparable<Version> {
        static final Pattern SHAPE = Pattern.compile("(v?)(\\d+(?:\\.\\d+)*)(.*)");

        static Version parse(String text) {
            Matcher m = SHAPE.matcher(text);
            if (!m.matches()) {
                return null;
            }
            int[] numbers;
            try {
                numbers = Pattern.compile("\\.").splitAsStream(m.group(2)).mapToInt(Integer::parseInt).toArray();
            } catch (NumberFormatException e) {
                return null; // a date-stamped snapshot too long for an int
            }
            return new Version(text, m.group(1), numbers, m.group(3));
        }

        boolean sameShape(Version other) {
            return prefix.equals(other.prefix) && qualifier.equalsIgnoreCase(other.qualifier);
        }

        /** 0.x moves its minor as others move their major. */
        String major() {
            return numbers[0] == 0 && numbers.length > 1 ? "0." + numbers[1] : String.valueOf(numbers[0]);
        }

        @Override
        public int compareTo(Version other) {
            for (int i = 0; i < Math.max(numbers.length, other.numbers.length); i++) {
                int a = i < numbers.length ? numbers[i] : 0;
                int b = i < other.numbers.length ? other.numbers[i] : 0;
                if (a != b) {
                    return Integer.compare(a, b);
                }
            }
            return 0;
        }
    }

    /** Each is null when nothing newer is out. */
    record Targets(String sameMajor, String latest) {}

    static Targets targets(String current, List<Version> releases) {
        Version now = Version.parse(current);
        if (now == null || releases == null) {
            return new Targets(null, null);
        }
        Version sameMajor = null;
        Version latest = null;
        for (Version v : releases) {
            if (!v.sameShape(now) || v.compareTo(now) <= 0) {
                continue;
            }
            if (latest == null || v.compareTo(latest) > 0) {
                latest = v;
            }
            if (v.major().equals(now.major()) && (sameMajor == null || v.compareTo(sameMajor) > 0)) {
                sameMajor = v;
            }
        }
        return new Targets(render(now, sameMajor), render(now, latest));
    }

    /** A reference that names only a major, as actions/checkout@v7 does, moves to a major. */
    static String render(Version now, Version target) {
        if (target == null) {
            return null;
        }
        if (now.numbers().length == 1) {
            return target.numbers()[0] == now.numbers()[0] ? null : now.prefix() + target.numbers()[0];
        }
        return target.text();
    }

    // --- output ---

    static String label(Dependency dependency) {
        return switch (dependency.source()) {
            case GRADLE_PLUGIN -> dependency.name() + " (plugin)";
            case NPM -> dependency.name() + " (npm)";
            case GITHUB_ACTION -> dependency.name() + " (action)";
            default -> dependency.name();
        };
    }

    static void report(List<Declaration> declarations, Map<Dependency, List<Version>> releases) {
        // one row per dependency and current version; a dependency on two rows has drifted between files
        Map<String, List<Declaration>> rows = new TreeMap<>();
        for (Declaration d : declarations) {
            rows.computeIfAbsent(label(d.dependency()) + "\u0000" + d.version(), k -> new ArrayList<>()).add(d);
        }
        Map<Dependency, Long> versionsPerDependency = new LinkedHashMap<>();
        for (String key : rows.keySet()) {
            Dependency dependency = rows.get(key).getFirst().dependency();
            versionsPerDependency.merge(dependency, 1L, Long::sum);
        }

        List<String[]> table = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        int current = 0;
        for (List<Declaration> row : rows.values()) {
            Declaration first = row.getFirst();
            List<Version> found = releases.get(first.dependency());
            if (found.isEmpty()) {
                unknown.add(label(first.dependency()));
                continue;
            }
            Targets targets = targets(first.version(), found);
            if (targets.latest() == null) {
                current++;
                continue;
            }
            String where = row.stream().map(d -> d.file() + ":" + d.line()).reduce((a, b) -> a + ", " + b).orElseThrow();
            String drift = versionsPerDependency.get(first.dependency()) > 1 ? " (drifted)" : "";
            table.add(new String[] {
                    label(first.dependency()) + drift,
                    first.version(),
                    targets.sameMajor() == null ? "-" : targets.sameMajor(),
                    targets.latest().equals(targets.sameMajor()) ? "" : targets.latest(),
                    where});
        }

        if (table.isEmpty()) {
            System.out.println("Every dependency is on its latest release (" + current + " checked).");
        } else {
            table.addFirst(new String[] {"dependency", "current", "same major", "newer major", "declared at"});
            int[] widths = new int[4];
            for (String[] cells : table) {
                for (int i = 0; i < 4; i++) {
                    widths[i] = Math.max(widths[i], cells[i].length());
                }
            }
            for (String[] cells : table) {
                System.out.printf("%-" + widths[0] + "s  %-" + widths[1] + "s  %-" + widths[2] + "s  %-" + widths[3] + "s  %s%n",
                        (Object[]) cells);
            }
            System.out.println();
            System.out.println((table.size() - 1) + " to move, " + current + " already current.");
        }
        if (!unknown.isEmpty()) {
            System.out.println("No releases found for: " + String.join(", ", unknown));
        }
    }

    static void reportImages(List<Path> files) throws IOException {
        List<String> images = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            Pattern pattern = name.equals("Dockerfile") ? DOCKER_FROM
                    : name.equals("pom.xml") ? POM_IMAGE
                    : name.endsWith(".java") && file.startsWith("spider-silk-gradle-plugin/src/main") ? JAVA_IMAGE
                    : null;
            if (pattern == null) {
                continue;
            }
            String text = Files.readString(file);
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                int line = (int) text.substring(0, m.start(1)).chars().filter(c -> c == '\n').count() + 1;
                images.add("  " + m.group(1) + "  " + file + ":" + line);
            }
        }
        if (!images.isEmpty()) {
            System.out.println();
            System.out.println("Base images, not looked up (check for a newer JDK line or distro by hand):");
            images.forEach(System.out::println);
        }
    }

    static void applyMoves(Map<Declaration, String> moves) {
        System.out.println();
        if (moves.isEmpty()) {
            System.out.println("Nothing to apply.");
            return;
        }
        Map<Path, List<Declaration>> byFile = new TreeMap<>();
        moves.keySet().forEach(d -> byFile.computeIfAbsent(d.file(), k -> new ArrayList<>()).add(d));
        byFile.forEach((file, inFile) -> {
            try {
                StringBuilder text = new StringBuilder(Files.readString(file));
                // from the end, so an edit never shifts the offsets of the ones still to come
                inFile.sort(Comparator.comparingInt(Declaration::start).reversed());
                for (Declaration d : inFile) {
                    text.replace(d.start(), d.end(), moves.get(d));
                }
                Files.writeString(file, text);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        moves.forEach((d, target) -> System.out.println(
                "moved " + label(d.dependency()) + " " + d.version() + " -> " + target + "  " + d.file() + ":" + d.line()));
    }

    /** Words too common to tell one dependency's mention from another's. */
    static final Set<String> GENERIC = Set.of(
            "api", "core", "plugin", "gradle", "maven", "servlet", "server", "embed", "runtime", "native", "tools", "actions");

    /**
     * Lines that still carry a moved dependency's old version beside a word from its name: prose, badges, and
     * code samples no scanner reads. CHANGELOG.md and notes/ record history and are left out.
     */
    static void reportMentions(List<Path> files, Map<Declaration, String> moves) throws IOException {
        Map<String, Set<String>> wordsByOld = new TreeMap<>();
        for (Declaration d : moves.keySet()) {
            String name = d.dependency().name().replace(".gradle.plugin", "");
            String artifact = name.substring(name.lastIndexOf(name.contains(":") ? ':' : name.contains("/") ? '/' : '.') + 1);
            Set<String> words = wordsByOld.computeIfAbsent(d.version(), k -> new LinkedHashSet<>());
            for (String word : artifact.toLowerCase().split("[-.:/]")) {
                if (word.length() >= 3 && !GENERIC.contains(word)) {
                    words.add(word);
                }
            }
        }
        List<String> mentions = new ArrayList<>();
        for (Path file : files) {
            String name = file.toString();
            if (name.equals("CHANGELOG.md") || name.startsWith("notes/") || name.endsWith("package-lock.json")
                    || name.endsWith(".svg") || name.endsWith(".drawio") || !isText(file)) {
                continue;
            }
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                for (Map.Entry<String, Set<String>> entry : wordsByOld.entrySet()) {
                    Pattern old = Pattern.compile("(?<![\\w.])" + Pattern.quote(entry.getKey()) + "(?!\\.?\\d)");
                    String lower = line.toLowerCase();
                    if (old.matcher(line).find() && entry.getValue().stream().anyMatch(lower::contains)) {
                        mentions.add("  " + file + ":" + (i + 1) + ": " + line.strip());
                        break;
                    }
                }
            }
        }
        if (!mentions.isEmpty()) {
            System.out.println();
            System.out.println("Other mentions of an old version (update what describes the build, keep what records a measurement):");
            mentions.forEach(System.out::println);
        }
    }

    static boolean isText(Path file) {
        try {
            Files.readString(file);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
