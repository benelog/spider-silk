package spidersilk.handlebars;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Map;
import java.util.Objects;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.cache.ConcurrentMapTemplateCache;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;

import spidersilk.App;
import spidersilk.TemplateRenderer;

/**
 * Handlebars.java (https://github.com/jknack/handlebars.java) integration.
 * {@code {{name}}} is HTML-escaped; {@code {{{name}}}} is not.
 *
 * <pre>{@code
 * app.templates(new HandlebarsTemplates("hbs"));
 *
 * WebResponse.template("deck", model);   // renders classpath:/hbs/deck.hbs
 * }</pre>
 *
 * <p>Unlike jte, Handlebars is not what an {@link App} renders with unless told
 * otherwise: it is a module of its own, and {@link App#templates} puts it in
 * place of the default.
 */
public final class HandlebarsTemplates implements TemplateRenderer {

    /** The extension appended to a template name by default. */
    public static final String DEFAULT_SUFFIX = ".hbs";

    private final Handlebars handlebars;
    private String suffix = DEFAULT_SUFFIX;

    /**
     * Looks up templates under the given classpath root, e.g. "hbs", with
     * compiled templates cached — the loader appends no extension of its own,
     * so {@link #suffix} is the only one in play.
     */
    public HandlebarsTemplates(String classpathRoot) {
        this(new Handlebars(new ClassPathTemplateLoader(absolute(classpathRoot), ""))
                .with(new ConcurrentMapTemplateCache()));
    }

    /** For helpers, a cache, or an escaping strategy of your own. */
    public HandlebarsTemplates(Handlebars handlebars) {
        this.handlebars = Objects.requireNonNull(handlebars, "handlebars");
    }

    /**
     * The extension appended to the name a template body carries, so a handler
     * writes the name and nothing else:
     *
     * <pre>{@code
     * app.templates(new HandlebarsTemplates("hbs").suffix(".html"));
     *
     * WebResponse.template("deck", model);   // renders classpath:/hbs/deck.html
     * }</pre>
     *
     * <p>The default is {@code ".hbs"}. It is appended, never checked for: a
     * name that already ends in the suffix would be looked up twice over.
     */
    public HandlebarsTemplates suffix(String suffix) {
        this.suffix = Objects.requireNonNull(suffix, "suffix");
        return this;
    }

    @Override
    public void render(String template, Map<String, Object> model, Writer out) {
        try {
            Template compiled = handlebars.compile(template + suffix);
            compiled.apply(Context.newContext(model), out);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot render the template " + template + suffix, e);
        }
    }

    private static String absolute(String classpathRoot) {
        Objects.requireNonNull(classpathRoot, "classpathRoot");
        return classpathRoot.startsWith("/") ? classpathRoot : "/" + classpathRoot;
    }
}
