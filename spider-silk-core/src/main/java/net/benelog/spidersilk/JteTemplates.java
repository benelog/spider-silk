package net.benelog.spidersilk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import gg.jte.CodeResolver;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.WriterOutput;
import gg.jte.resolve.ResourceCodeResolver;

/**
 * jte (https://jte.gg) integration.
 * With ContentType.Html, ${} output is HTML-escaped automatically.
 *
 * <p>This is what an {@link App} renders with unless {@link App#templates} says
 * otherwise, reading {@code classpath:/jte} — so a plain {@code new App()}
 * already renders {@code WebResponse.template("deck")} from
 * {@code classpath:/jte/deck.jte}.
 */
public final class JteTemplates implements TemplateRenderer {

    /** The classpath root an {@link App} reads templates from by default. */
    public static final String DEFAULT_ROOT = "jte";

    /** The extension appended to a template name by default. */
    public static final String DEFAULT_SUFFIX = ".jte";

    private final TemplateEngine engine;
    private String suffix = DEFAULT_SUFFIX;

    /** Looks up templates under the given classpath root, e.g. "jte". */
    public JteTemplates(String classpathRoot) {
        this(new ResourceCodeResolver(classpathRoot));
    }

    /**
     * Compiles the templates the resolver finds into a temporary directory of
     * this instance's own, deleted when the JVM exits.
     */
    public JteTemplates(CodeResolver codeResolver) {
        try {
            this.engine = TemplateEngine.create(codeResolver, ClassDirectories.create(), ContentType.Html);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the jte compilation directory", e);
        }
    }

    /** For precompiled templates or a customized engine. */
    public JteTemplates(TemplateEngine engine) {
        this.engine = engine;
    }

    /**
     * The extension appended to the name a template body carries, so a handler
     * writes the name and nothing else:
     *
     * <pre>{@code
     * app.templates(new JteTemplates("jte").suffix(".html"));
     *
     * WebResponse.template("deck"); // renders classpath:/jte/deck.html
     * }</pre>
     *
     * <p>The default is {@code ".jte"}. It is appended, never checked for: a
     * name that already ends in the suffix would be looked up twice over.
     */
    public JteTemplates suffix(String suffix) {
        this.suffix = Objects.requireNonNull(suffix, "suffix");
        return this;
    }

    @Override
    public void render(String template, Model model, Writer out) {
        engine.render(template + suffix, model.asMap(), new WriterOutput(out));
    }

    /**
     * The directories jte compiles templates into, deleted by one shutdown hook
     * for the whole JVM.
     *
     * <p>Each instance keeps a directory of its own: two renderers over two
     * roots can hold templates of one name, whose classes would overwrite each
     * other in a shared one. The directory cannot go any earlier than the JVM,
     * because the engine loads classes from it for as long as it renders, and
     * nothing tells a renderer that its last render is over. Left alone, every
     * run left a {@code jte-classes*} directory behind in the temp directory.
     */
    private static final class ClassDirectories {

        private static final Set<Path> CREATED = ConcurrentHashMap.newKeySet();

        static {
            Runtime.getRuntime().addShutdownHook(new Thread(ClassDirectories::deleteAll, "jte-classes-cleanup"));
        }

        private ClassDirectories() {
        }

        static Path create() throws IOException {
            Path directory = Files.createTempDirectory("jte-classes");
            CREATED.add(directory);
            return directory;
        }

        private static void deleteAll() {
            for (Path directory : CREATED) {
                try (Stream<Path> tree = Files.walk(directory)) {
                    tree.sorted(Comparator.reverseOrder()).forEach(ClassDirectories::deleteQuietly);
                } catch (IOException | UncheckedIOException e) {
                    // Best effort at exit: what cannot be walked stays, as all of it used to.
                }
            }
        }

        private static void deleteQuietly(Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                // Best effort at exit: a file that cannot be deleted stays, and so does its directory.
            }
        }
    }
}
