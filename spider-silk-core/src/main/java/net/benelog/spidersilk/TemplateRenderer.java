package net.benelog.spidersilk;

import java.io.Writer;

/**
 * Template engine integration point. The default is {@link JteTemplates}, over
 * {@code classpath:/jte}.
 *
 * <p>{@code template} is the name a handler wrote, without an extension; an
 * implementation appends whatever its engine expects.
 *
 * <p>{@code model} is the {@link Model} the handler passed, unchanged. An engine
 * that takes a map is handed {@link Model#asMap()}, which cannot be changed,
 * keeps the order the entries were given, and holds null values.
 */
public interface TemplateRenderer {

    void render(String template, Model model, Writer out);
}
