package steelspider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.util.Map;

import gg.jte.CodeResolver;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.WriterOutput;
import gg.jte.resolve.ResourceCodeResolver;

/**
 * jte (https://jte.gg) integration.
 * With ContentType.Html, ${} output is HTML-escaped automatically.
 */
public final class JteTemplates implements TemplateRenderer {

    private final TemplateEngine engine;

    /** Looks up .jte templates under the given classpath root, e.g. "jte". */
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

    @Override
    public void render(String template, Map<String, Object> model, Writer out) {
        engine.render(template, model, new WriterOutput(out));
    }
}
