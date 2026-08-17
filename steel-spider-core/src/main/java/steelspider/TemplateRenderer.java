package steelspider;

import java.io.Writer;
import java.util.Map;

/** Template engine integration point. The default implementation is {@link JteTemplates}. */
public interface TemplateRenderer {

    void render(String template, Map<String, Object> model, Writer out);
}
