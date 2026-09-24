package net.benelog.spidersilk;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.zip.CRC32;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;

/**
 * Files served off the classpath or off a directory, with the caching headers
 * that stop a browser re-downloading the stylesheet on every page load.
 *
 * <p>{@code classpath:/public} is served at the root without being asked for;
 * this is for a directory, a hosted path, or a cache policy of your own.
 *
 * <pre>{@code
 * app.staticFiles("/assets");                       // classpath:/assets/* at /*
 *
 * app.staticFiles(new StaticFiles("/public")
 *         .hostedPath("/assets")                    // classpath:/public/* at /assets/*
 *         .maxAge(Duration.ofDays(365)));           // for fingerprinted file names
 *
 * app.staticFiles(                                  // both, in the order given
 *         new StaticFiles("/public"),
 *         StaticFiles.directory(Path.of("/srv/uploads")).hostedPath("/uploads"));
 * }</pre>
 *
 * <p>Every response carries an {@code ETag} and {@code Last-Modified} derived
 * from the resource itself, so a conditional request comes back as a bodyless
 * 304. A file whose modification time a reproducible build stamped — Jib
 * stamps every file in an image with the same one — gets a tag derived from
 * its content instead, and no {@code Last-Modified}, since that time would
 * call a changed file unchanged. The default {@code Cache-Control} is
 * {@code no-cache}, which means
 * "cache it, but check with me first" — correct for files whose name never
 * changes. {@link #maxAge(Duration)} is for the other kind, where the name
 * carries a content hash and the file at that name can never change.
 *
 * <p>A directory root is a path-traversal surface a classpath lookup does not
 * have, so {@link #directory(Path)} answers only for a regular file whose real
 * path — symbolic links resolved — lies under the root's real path. Anything
 * else is not a file this serves, and routing carries on as if it were absent.
 *
 * <p>{@link #precompressed()} answers with a {@code .br} or {@code .gz} sibling
 * of the file when the client will take that encoding, which is the only way
 * core answers brotli at all — the JDK ships no encoder — and the only way an
 * asset is not deflated again on every request that asks for it.
 */
public final class StaticFiles {

    /** The classpath root an {@link App} serves files from by default. */
    public static final String DEFAULT_ROOT = "/public";

    private static final String REVALIDATE = "no-cache";

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    /**
     * The earliest modification time taken to be one a write left. Before it,
     * the time is a stamp a reproducible build put on every file alike: Jib and
     * Nix stamp 1970-01-01T00:00:01Z, Cloud Native Buildpacks
     * 1980-01-01T00:00:01Z, and a zip entry holds nothing earlier than 1980. No
     * file an application serves was last written before 2000.
     */
    private static final long WRITTEN_SINCE = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli();

    /**
     * The encodings a sibling can carry, in the order they are preferred: brotli
     * first, being the smaller of the two and the one nothing else can produce.
     * The name on the left is the {@code Accept-Encoding} token, the one on the
     * right the file extension — {@code gzip} and {@code .gz} are not the same
     * spelling.
     */
    private static final List<Encoding> ENCODINGS =
            List.of(new Encoding("br", ".br"), new Encoding("gzip", ".gz"));

    private final Source source;

    /**
     * The content checksums worked out for files whose modification time is a
     * stamp, by path, time, length, and the file's change time. A file changes
     * none of them within a deployment of an image, so each is read for its
     * checksum once rather than on every request.
     *
     * <p>The change time is what catches a file rewritten while the application
     * runs. A copy that keeps the stamp, such as {@code cp -a}, {@code tar}, or
     * {@code rsync -a} into a {@code directory(...)}, keeps the modification
     * time, and a small edit can keep the length; the change time is the file
     * system's own, and no copy can set it.
     */
    private final Map<ChecksumKey, Long> checksums = new ConcurrentHashMap<>();

    private String hostedPath = "";
    private String cacheControl = REVALIDATE;
    private boolean precompressed;

    /** @param classpathRoot the classpath directory to serve, e.g. "/public" */
    public StaticFiles(String classpathRoot) {
        this(classpathRoot, StaticFiles.class::getResource);
    }

    /**
     * A classpath root looked up through something other than this class's own
     * loader, so a test can stand a packaged or an instrumented resource in for
     * the real classpath.
     *
     * @param lookup the URL for an absolute resource name, or null when none
     */
    StaticFiles(String classpathRoot, Function<String, @Nullable URL> lookup) {
        this(new ClasspathSource(withoutTrailingSlash(
                Objects.requireNonNull(classpathRoot, "classpathRoot")), lookup));
    }

    private StaticFiles(Source source) {
        this.source = source;
    }

    /**
     * Files served from a directory on disk rather than the classpath: an
     * upload directory, a volume mounted beside the jar, whatever a separate
     * build writes into.
     *
     * <p>The root is read per request, not at construction, so a volume that is
     * mounted after the application starts needs no restart, and one that is
     * never mounted answers 404 rather than failing to boot.
     *
     * @param root the directory to serve
     */
    public static StaticFiles directory(Path root) {
        return new StaticFiles(new DirectorySource(Objects.requireNonNull(root, "root")));
    }

    /**
     * The copy {@link App#staticFiles(StaticFiles...)} keeps, so a reference the
     * caller holds on to cannot move or re-cache what an application has already
     * been handed. The source is never changed once built, so it is shared.
     */
    StaticFiles copy() {
        StaticFiles copy = new StaticFiles(source);
        copy.hostedPath = hostedPath;
        copy.cacheControl = cacheControl;
        copy.precompressed = precompressed;
        return copy;
    }

    /** The URL prefix the files appear under. The default, "/", is the root. */
    public StaticFiles hostedPath(String hostedPath) {
        this.hostedPath = withoutTrailingSlash(Objects.requireNonNull(hostedPath, "hostedPath"));
        return this;
    }

    /**
     * How long a client may reuse a file without asking. Only safe when the file
     * name changes whenever the content does.
     */
    public StaticFiles maxAge(Duration maxAge) {
        this.cacheControl = "public, max-age=" + maxAge.toSeconds();
        return this;
    }

    /** The raw Cache-Control header, when neither default fits. */
    public StaticFiles cacheControl(String cacheControl) {
        this.cacheControl = Objects.requireNonNull(cacheControl, "cacheControl");
        return this;
    }

    /**
     * Answers with the {@code app.css.br} or {@code app.css.gz} a build left
     * beside {@code app.css}, whenever the request will take that encoding.
     * Brotli wins where both exist and the client takes both. The answer
     * describes the original either way: its {@code Content-Type}, since a
     * {@code .gz} extension is the encoding and not the type, and its
     * validators, so a browser revalidating across encodings keeps its 304.
     *
     * <p>A sibling older than the file it sits next to is a build that did not
     * rerun, and is passed over rather than served as content that no longer
     * exists. Every answer from this root then carries
     * {@code Vary: Accept-Encoding}, sibling found or not, so a shared cache
     * never hands encoded bytes to a client that cannot read them.
     */
    public StaticFiles precompressed() {
        this.precompressed = true;
        return this;
    }

    /**
     * The response for the file this path names, or null when it names none and
     * routing should carry on. The body is a stream rather than a byte array, so
     * a large file never lands in memory whole.
     *
     * <p>Nothing is held open once this returns. The metadata is read off the
     * resource here, and the file is opened only by the body writer, so a
     * response a filter replaces, one a filter throws over, a HEAD, and a 304
     * leave nothing behind to release. The one read here is the checksum of a
     * file whose modification time is a stamp, once per version of the file,
     * and that stream is closed before this returns.
     */
    @Nullable WebResponse resolve(String path, HttpServletRequest req) throws IOException {
        String relative = relativePath(path);
        if (relative == null) {
            return null;
        }
        Resource resource = source.find(relative);
        if (resource == null) {
            return null;
        }

        long lastModified = resource.lastModified();
        long length = resource.length();
        Encoded encoded = precompressed ? sibling(relative, lastModified, req) : null;

        WebResponse response = WebResponse.empty().header("Cache-Control", cacheControl);
        if (precompressed) {
            response = response.vary("Accept-Encoding");
        }
        boolean written = isWriteTime(lastModified);
        String etag = written
                ? etag(lastModified, length)
                : contentTag(relative, resource, lastModified, length);
        response = response.header("ETag", encoded == null ? etag : "W/" + etag);
        if (written) {
            response = response.header("Last-Modified", httpDate(lastModified));
        }
        if (isUnchanged(req, etag, written ? lastModified : -1)) {
            return response.status(HttpStatus.NOT_MODIFIED);
        }

        Resource body = encoded == null ? resource : encoded.resource();
        if (encoded != null) {
            response = response.header("Content-Encoding", encoded.encoding());
        }
        long bodyLength = body.length();
        // A HEAD keeps the writer too: compression rewrites the answer after this
        // point, and the writer opens the file only if it runs.
        response = response
                .body(new WebResponse.Streamed(out -> {
                    try (InputStream in = body.open()) {
                        in.transferTo(out);
                    }
                }))
                .contentType(ContentTypes.byPath(relative));
        return bodyLength >= 0
                ? response.header("Content-Length", Long.toString(bodyLength))
                : response;
    }

    /**
     * The pre-compressed file sitting next to this one that the request will
     * take, or null when the build left none, the client reads none, or the one
     * that is there is older than the file it claims to be a copy of.
     */
    private @Nullable Encoded sibling(String relative, long lastModified, HttpServletRequest req)
            throws IOException {
        String accepted = req.getHeader("Accept-Encoding");
        for (Encoding encoding : ENCODINGS) {
            if (!AcceptHeader.accepts(accepted, encoding.token())) {
                continue;
            }
            Resource candidate = source.find(relative + encoding.extension());
            if (candidate == null) {
                continue;
            }
            if (isStale(candidate, lastModified)) {
                continue;
            }
            return new Encoded(encoding.token(), candidate);
        }
        return null;
    }

    /**
     * Whether the sibling is older than the file it sits next to, which is a
     * build that compressed an earlier version and did not rerun. A time neither
     * of them reports is no evidence of staleness, so the sibling still answers.
     */
    private boolean isStale(Resource sibling, long lastModified) {
        long siblingModified = sibling.lastModified();
        return lastModified > 0 && siblingModified > 0 && siblingModified < lastModified;
    }

    /**
     * The IMF-fixdate HTTP wants: a two-digit day, English month and day names,
     * and GMT. {@code RFC_1123_DATE_TIME} writes a single-digit day, which a
     * client is not obliged to parse back.
     */
    private static String httpDate(long epochMillis) {
        return HTTP_DATE.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC));
    }

    /**
     * The path under {@link #hostedPath} a request asks for, or null when the
     * request is not for this directory at all.
     */
    private @Nullable String relativePath(String path) {
        if (path.contains("..") || !path.startsWith(hostedPath)) {
            return null;
        }
        String relative = path.substring(hostedPath.length());
        if (relative.isEmpty() || !relative.startsWith("/") || relative.endsWith("/")) {
            return null;
        }
        return relative;
    }

    /**
     * Content changes imply a new modification time or a new length, and the
     * file is immutable within a deployment, so the two together identify it.
     */
    private String etag(long lastModified, long length) {
        return "\"" + Long.toHexString(lastModified) + "-" + Long.toHexString(length) + "\"";
    }

    /**
     * Whether a modification time is one a write left, and so tells one version
     * of a file from the next. A stamp is the same for every version, and so is
     * no time at all.
     */
    private static boolean isWriteTime(long lastModified) {
        return lastModified >= WRITTEN_SINCE;
    }

    /**
     * The tag of a file whose modification time is a stamp: the CRC-32 of its
     * content and its length. Two releases of an image give a stylesheet the
     * same time and, after a one-character edit, the same length, so only the
     * content can tell them apart.
     */
    private String contentTag(String relative, Resource resource, long lastModified, long length)
            throws IOException {
        long changed = resource.changeTime();
        if (changed < 0) {
            // Nothing tells a rewrite apart from the file already read, so read it each time.
            return "\"" + Long.toHexString(resource.checksum()) + "-" + Long.toHexString(length) + "\"";
        }
        ChecksumKey key = new ChecksumKey(relative, lastModified, length, changed);
        Long checksum = checksums.get(key);
        if (checksum == null) {
            checksum = resource.checksum();
            checksums.put(key, checksum);
        }
        return "\"" + Long.toHexString(checksum) + "-" + Long.toHexString(length) + "\"";
    }

    /** What a content checksum is kept under: a path, and the times and length it had. */
    private record ChecksumKey(String relative, long lastModified, long length, long changed) {
    }

    /**
     * Whether the client already holds this version. {@code If-None-Match}
     * decides when it is there, and {@code If-Modified-Since} is read only
     * against a time a write left, which a negative {@code lastModified} says
     * this is not.
     */
    private boolean isUnchanged(HttpServletRequest req, String etag, long lastModified) {
        String ifNoneMatch = req.getHeader("If-None-Match");
        if (ifNoneMatch != null) {
            for (String candidate : ifNoneMatch.split(",", -1)) {
                String trimmed = candidate.trim();
                if (trimmed.equals("*") || trimmed.equals(etag) || trimmed.equals("W/" + etag)) {
                    return true;
                }
            }
            return false;
        }
        if (lastModified < 0) {
            return false;
        }
        long ifModifiedSince = ifModifiedSince(req);
        return ifModifiedSince >= 0 && lastModified / 1000 * 1000 <= ifModifiedSince;
    }

    private long ifModifiedSince(HttpServletRequest req) {
        try {
            return req.getDateHeader("If-Modified-Since");
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private static String withoutTrailingSlash(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (!trimmed.isEmpty() && !trimmed.startsWith("/")) {
            throw new IllegalArgumentException("Path must start with \"/\": " + path);
        }
        return trimmed;
    }

    /** One encoding a sibling can carry: its header token and its extension. */
    private record Encoding(String token, String extension) {
    }

    /** The sibling that answers, and the encoding to announce it under. */
    private record Encoded(String encoding, Resource resource) {
    }

    /** Where the bytes come from: a classpath root or a directory. */
    private interface Source {

        /**
         * The file the given path — always starting with "/" — names under this
         * root, or null when the root holds no such file.
         */
        @Nullable Resource find(String relative) throws IOException;
    }

    /**
     * One file a {@link Source} found: the validators it can be identified by,
     * and a way to read it. Finding one holds nothing open; only
     * {@link #open()} does, and the stream it returns is the caller's to close.
     */
    private interface Resource {

        long lastModified();

        long length();

        InputStream open() throws IOException;

        /**
         * A time that changes whenever the content does, and that nothing can
         * set back, or -1 when the source has none. A resource that cannot
         * change while the application runs answers 0.
         */
        default long changeTime() {
            return 0;
        }

        /** The CRC-32 of the content, read through {@link #open()} unless the source already holds it. */
        default long checksum() throws IOException {
            CRC32 crc = new CRC32();
            try (InputStream in = open()) {
                byte[] chunk = new byte[8192];
                for (int read = in.read(chunk); read >= 0; read = in.read(chunk)) {
                    crc.update(chunk, 0, read);
                }
            }
            return crc.getValue();
        }
    }

    private record ClasspathSource(String root, Function<String, @Nullable URL> lookup)
            implements Source {

        @Override
        public @Nullable Resource find(String relative) throws IOException {
            URL url = lookup.apply(root + relative);
            if (url == null) {
                return null;
            }
            Path file = fileOf(url);
            if (file == null) {
                return UrlResource.of(url);
            }
            // An exploded classpath is a directory on disk, whose attributes are
            // read without opening the file. It hands back directories too, and a
            // listing is not a file.
            try {
                BasicFileAttributes attributes =
                        Files.readAttributes(file, BasicFileAttributes.class);
                return attributes.isRegularFile() ? new FileResource(file, attributes) : null;
            } catch (IOException e) {
                return null;
            }
        }

        /** The file a {@code file:} URL names, or null for any other kind of URL. */
        private static @Nullable Path fileOf(URL url) {
            if (!"file".equals(url.getProtocol())) {
                return null;
            }
            try {
                return Path.of(url.toURI());
            } catch (URISyntaxException | IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * A classpath resource that is not a plain file, such as an entry in a jar.
     * Asking a {@link URLConnection} for the modification time or the length
     * connects it, and what connecting opens stays open until the connection's
     * stream is closed. So the metadata is read once, the connection is released
     * straight away, and the body opens the URL afresh, if it is ever written.
     *
     * <p>A jar entry is read through {@link JarURLConnection} rather than the
     * connection's headers. Its {@code Last-Modified} header comes from a second
     * connection to the jar file, which opens the jar once per request and
     * which closing the entry's stream never releases. The time asked for
     * instead is the same one: the jar file's own, since a reproducible build
     * stamps every entry with one fixed date.
     *
     * <p>A jar entry's CRC-32 is in the jar's central directory, so it is kept
     * too, and a stamped jar is not read again for a checksum it already holds.
     * Anything but a jar entry keeps -1 there.
     */
    private record UrlResource(URL url, long lastModified, long length, long crc)
            implements Resource {

        static @Nullable UrlResource of(URL url) throws IOException {
            URLConnection connection = url.openConnection();
            try {
                if (connection instanceof JarURLConnection jar) {
                    JarEntry entry = jar.getJarEntry();
                    return entry.isDirectory()
                            ? null
                            : new UrlResource(url, jarModified(jar, entry), entry.getSize(),
                                    entry.getCrc());
                }
                return new UrlResource(url,
                        connection.getLastModified(), connection.getContentLengthLong(), -1);
            } finally {
                release(connection);
            }
        }

        /** The jar file's modification time, or the entry's when the jar is not a file. */
        private static long jarModified(JarURLConnection jar, JarEntry entry) {
            Path file = ClasspathSource.fileOf(jar.getJarFileURL());
            if (file != null) {
                try {
                    return Files.getLastModifiedTime(file).toMillis();
                } catch (IOException e) {
                    // Fall through to the entry's own time.
                }
            }
            return Math.max(entry.getTime(), 0);
        }

        private static void release(URLConnection connection) {
            try (InputStream ignored = connection.getInputStream()) {
                // Opened only to be closed, which releases what the connection holds.
            } catch (IOException e) {
                // Nothing was opened, so there is nothing to release.
            }
        }

        @Override
        public InputStream open() throws IOException {
            return url.openStream();
        }

        @Override
        public long checksum() throws IOException {
            return crc >= 0 ? crc : Resource.super.checksum();
        }
    }

    /**
     * A directory root, which unlike the classpath can be walked out of. The
     * guard is the real path: the file the request resolves to, with every
     * symbolic link followed, has to still lie under the root's own real path.
     */
    private record DirectorySource(Path root) implements Source {

        @Override
        public @Nullable Resource find(String relative) {
            Path candidate;
            try {
                candidate = root.resolve(relative.substring(1));
            } catch (InvalidPathException e) {
                return null;
            }
            try {
                Path real = candidate.toRealPath();
                if (!real.startsWith(root.toRealPath())) {
                    return null;
                }
                BasicFileAttributes attributes =
                        Files.readAttributes(real, BasicFileAttributes.class);
                return attributes.isRegularFile()
                        ? new FileResource(real, attributes)
                        : null;
            } catch (IOException e) {
                // No such file, an unreadable one, or a root that is not mounted:
                // all of them mean this root does not answer for the path.
                return null;
            }
        }
    }

    private record FileResource(Path path, BasicFileAttributes attributes) implements Resource {

        @Override
        public long lastModified() {
            return attributes.lastModifiedTime().toMillis();
        }

        @Override
        public long length() {
            return attributes.size();
        }

        @Override
        public InputStream open() throws IOException {
            return Files.newInputStream(path);
        }

        /**
         * The POSIX change time, which every write moves and which no copy can
         * set. Read only for a stamped file, so a file with a real modification
         * time costs nothing more. -1 on a file system without one, such as
         * Windows, where the checksum is read on every request instead.
         */
        @Override
        public long changeTime() {
            try {
                return ((FileTime) Files.getAttribute(path, "unix:ctime")).to(TimeUnit.NANOSECONDS);
            } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
                return -1;
            }
        }
    }
}
