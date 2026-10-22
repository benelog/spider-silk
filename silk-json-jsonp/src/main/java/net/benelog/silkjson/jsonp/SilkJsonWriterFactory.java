package net.benelog.silkjson.jsonp;

import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Map;

import jakarta.json.JsonWriter;
import jakarta.json.JsonWriterFactory;

/** Makes writers that print prettily when the configuration says so. */
final class SilkJsonWriterFactory implements JsonWriterFactory {

    private final Config config;

    SilkJsonWriterFactory(Config config) {
        this.config = config;
    }

    @Override
    public JsonWriter createWriter(Writer writer) {
        return new SilkJsonWriter(Outputs.of(writer, config.prettyPrinting));
    }

    /** A writer writing UTF-8. */
    @Override
    public JsonWriter createWriter(OutputStream out) {
        return new SilkJsonWriter(Outputs.of(out, config.prettyPrinting));
    }

    @Override
    public JsonWriter createWriter(OutputStream out, Charset charset) {
        return new SilkJsonWriter(Outputs.of(out, charset, config.prettyPrinting));
    }

    @Override
    public Map<String, ?> getConfigInUse() {
        return config.printingInUse();
    }
}
