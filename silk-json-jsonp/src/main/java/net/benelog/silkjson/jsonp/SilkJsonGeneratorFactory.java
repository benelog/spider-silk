package net.benelog.silkjson.jsonp;

import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Map;

import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonGeneratorFactory;

/** Makes generators that print prettily when the configuration says so. */
final class SilkJsonGeneratorFactory implements JsonGeneratorFactory {

    private final Config config;

    SilkJsonGeneratorFactory(Config config) {
        this.config = config;
    }

    @Override
    public JsonGenerator createGenerator(Writer writer) {
        return new SilkJsonGenerator(Outputs.of(writer, config.prettyPrinting));
    }

    /** A generator writing UTF-8. */
    @Override
    public JsonGenerator createGenerator(OutputStream out) {
        return new SilkJsonGenerator(Outputs.of(out, config.prettyPrinting));
    }

    @Override
    public JsonGenerator createGenerator(OutputStream out, Charset charset) {
        return new SilkJsonGenerator(Outputs.of(out, charset, config.prettyPrinting));
    }

    @Override
    public Map<String, ?> getConfigInUse() {
        return config.printingInUse();
    }
}
