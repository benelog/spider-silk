package spidersilk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;

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

    public JteTemplates(CodeResolver codeResolver) {
        try {
            this.engine = TemplateEngine.create(codeResolver,
                    Files.createTempDirectory("jte-classes"), ContentType.Html);
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
     * WebResponse.template("deck", model);   // renders classpath:/jte/deck.html
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
    public void render(String template, Map<String, Object> model, Writer out) {
        engine.render(template + suffix, model, new WriterOutput(out));
    }
}
