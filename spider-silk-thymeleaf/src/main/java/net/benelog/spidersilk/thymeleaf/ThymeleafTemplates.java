package net.benelog.spidersilk.thymeleaf;

import java.io.Writer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.TemplateRenderer;

/**
 * Thymeleaf (https://www.thymeleaf.org) integration.
 * In {@link TemplateMode#HTML}, {@code th:text} is escaped and
 * {@code th:utext} is not.
 *
 * <pre>{@code
 * app.templates(new ThymeleafTemplates("thymeleaf"));
 *
 * WebResponse.template("deck", model);   // renders classpath:/thymeleaf/deck.html
 * }</pre>
 *
 * <p>Unlike jte, Thymeleaf is not what an {@link App} renders with unless told
 * otherwise: it is a module of its own, and {@link App#templates} puts it in
 * place of the default.
 *
 * <p>This is the servlet-free half of Thymeleaf — a template name, a model, and
 * a writer. There is no {@code WebContext}, so the expressions a Spring MVC
 * page reaches for ({@code #request}, {@code #session}, {@code @bean}) are not
 * there; a model entry is.
 */
public final class ThymeleafTemplates implements TemplateRenderer {

    /** The extension appended to a template name by default. */
    public static final String DEFAULT_SUFFIX = ".html";

    private final TemplateEngine engine;
    private String suffix = DEFAULT_SUFFIX;
    private Locale locale = Locale.getDefault();

    /**
     * Looks up templates under the given classpath root, e.g. "thymeleaf", as
     * UTF-8 HTML, with parsed templates cached — the resolver appends no
     * extension of its own, so {@link #suffix} is the only one in play.
     */
    public ThymeleafTemplates(String classpathRoot) {
        this(engineOver(classpathRoot));
    }

    /** For a resolver, a dialect, or a cache of your own. */
    public ThymeleafTemplates(TemplateEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * The extension appended to the name a template body carries, so a handler
     * writes the name and nothing else:
     *
     * <pre>{@code
     * app.templates(new ThymeleafTemplates("thymeleaf").suffix(".th.html"));
     *
     * WebResponse.template("deck", model);   // renders classpath:/thymeleaf/deck.th.html
     * }</pre>
     *
     * <p>The default is {@code ".html"}. It is appended, never checked for: a
     * name that already ends in the suffix would be looked up twice over.
     */
    public ThymeleafTemplates suffix(String suffix) {
        this.suffix = Objects.requireNonNull(suffix, "suffix");
        return this;
    }

    /**
     * The locale {@code #{...}} message expressions and {@code #numbers} format
     * against. The default is the JVM's, since a {@link TemplateRenderer} is
     * handed a model and not a request.
     */
    public ThymeleafTemplates locale(Locale locale) {
        this.locale = Objects.requireNonNull(locale, "locale");
        return this;
    }

    @Override
    public void render(String template, Map<String, Object> model, Writer out) {
        engine.process(template + suffix, new Context(locale, model), out);
    }

    private static TemplateEngine engineOver(String classpathRoot) {
        Objects.requireNonNull(classpathRoot, "classpathRoot");
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(classpathRoot.endsWith("/") ? classpathRoot : classpathRoot + "/");
        resolver.setSuffix("");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
