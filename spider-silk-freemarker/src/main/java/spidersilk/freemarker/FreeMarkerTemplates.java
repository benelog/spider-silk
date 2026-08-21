package spidersilk.freemarker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Map;
import java.util.Objects;

import freemarker.cache.ClassTemplateLoader;
import freemarker.core.HTMLOutputFormat;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

import spidersilk.App;
import spidersilk.TemplateRenderer;

/**
 * FreeMarker (https://freemarker.apache.org) integration.
 *
 * <pre>{@code
 * app.templates(new FreeMarkerTemplates("freemarker"));
 *
 * WebResponse.template("deck", model);   // renders classpath:/freemarker/deck.ftlh
 * }</pre>
 *
 * <p>Unlike jte, FreeMarker is not what an {@link App} renders with unless told
 * otherwise: it is a module of its own, and {@link App#templates} puts it in
 * place of the default.
 *
 * <p>FreeMarker escapes nothing until an output format says to, so the
 * {@code Configuration} this builds sets {@link HTMLOutputFormat} — {@code ${}}
 * is HTML-escaped, and {@code ${x?no_esc}} is the way out. Hand in a
 * {@code Configuration} of your own and that decision is yours to make again.
 */
public final class FreeMarkerTemplates implements TemplateRenderer {

    /**
     * The extension appended to a template name by default. FreeMarker reads
     * {@code .ftlh} as "this one is HTML" on its own, so a template carries the
     * escaping in its name as well as in the configuration.
     */
    public static final String DEFAULT_SUFFIX = ".ftlh";

    private final Configuration configuration;
    private String suffix = DEFAULT_SUFFIX;

    /**
     * Looks up templates under the given classpath root, e.g. "freemarker", as
     * UTF-8 HTML, with parsed templates cached — the loader appends no
     * extension of its own, so {@link #suffix} is the only one in play.
     */
    public FreeMarkerTemplates(String classpathRoot) {
        this(configurationOver(classpathRoot));
    }

    /** For an object wrapper, shared variables, or a loader of your own. */
    public FreeMarkerTemplates(Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /**
     * The extension appended to the name a template body carries, so a handler
     * writes the name and nothing else:
     *
     * <pre>{@code
     * app.templates(new FreeMarkerTemplates("freemarker").suffix(".ftl"));
     *
     * WebResponse.template("deck", model);   // renders classpath:/freemarker/deck.ftl
     * }</pre>
     *
     * <p>The default is {@code ".ftlh"}. It is appended, never checked for: a
     * name that already ends in the suffix would be looked up twice over.
     *
     * <p>A suffix other than {@code .ftlh} or {@code .ftlx} loses the escaping
     * FreeMarker reads out of the extension; the configuration this class
     * builds still sets HTML as the output format, so escaping survives the
     * change. A {@code Configuration} of your own has to say so itself.
     */
    public FreeMarkerTemplates suffix(String suffix) {
        this.suffix = Objects.requireNonNull(suffix, "suffix");
        return this;
    }

    @Override
    public void render(String template, Map<String, Object> model, Writer out) {
        String name = template + suffix;
        try {
            Template compiled = configuration.getTemplate(name);
            compiled.process(model, out);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot render the template " + name, e);
        } catch (TemplateException e) {
            throw new IllegalStateException("Cannot render the template " + name, e);
        }
    }

    private static Configuration configurationOver(String classpathRoot) {
        Objects.requireNonNull(classpathRoot, "classpathRoot");
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_34);
        configuration.setTemplateLoader(new ClassTemplateLoader(classLoader(), classpathRoot));
        configuration.setDefaultEncoding("UTF-8");
        configuration.setOutputFormat(HTMLOutputFormat.INSTANCE);

        // Rethrow, so a broken template reaches app.exception(...) instead of
        // being written half-rendered into the response as a stack trace.
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLogTemplateExceptions(false);
        configuration.setWrapUncheckedExceptions(true);
        configuration.setFallbackOnNullLoopVariable(false);
        return configuration;
    }

    private static ClassLoader classLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null
                ? contextClassLoader
                : FreeMarkerTemplates.class.getClassLoader();
    }
}
