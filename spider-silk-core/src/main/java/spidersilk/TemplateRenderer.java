package spidersilk;

import java.io.Writer;
import java.util.Map;

/**
 * Template engine integration point. The default is {@link JteTemplates}, over
 * {@code classpath:/jte}.
 *
 * <p>{@code template} is the name a handler wrote, without an extension; an
 * implementation appends whatever its engine expects.
 */
public interface TemplateRenderer {

    void render(String template, Map<String, Object> model, Writer out);
}
