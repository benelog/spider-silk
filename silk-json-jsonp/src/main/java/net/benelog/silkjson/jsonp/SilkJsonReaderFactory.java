package net.benelog.silkjson.jsonp;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Objects;

import jakarta.json.JsonReader;
import jakarta.json.JsonReaderFactory;

/** Makes readers whose objects keep a repeated key as the configuration's key strategy says. */
final class SilkJsonReaderFactory implements JsonReaderFactory {

    private final Config config;

    SilkJsonReaderFactory(Config config) {
        this.config = config;
    }

    @Override
    public JsonReader createReader(Reader reader) {
        Objects.requireNonNull(reader, "reader");
        return new SilkJsonReader(() -> Sources.of(reader), reader, config.keyStrategy);
    }

    /** A reader of a byte stream whose encoding is told from its first bytes. */
    @Override
    public JsonReader createReader(InputStream in) {
        Objects.requireNonNull(in, "in");
        return new SilkJsonReader(() -> Sources.detected(in), in, config.keyStrategy);
    }

    @Override
    public JsonReader createReader(InputStream in, Charset charset) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(charset, "charset");
        return new SilkJsonReader(() -> Sources.decoded(in, charset), in, config.keyStrategy);
    }

    @Override
    public Map<String, ?> getConfigInUse() {
        return config.keysInUse();
    }
}
