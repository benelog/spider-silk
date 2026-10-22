package net.benelog.silkjson.jsonp;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Objects;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonStructure;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParserFactory;

/** Makes parsers whose {@code getObject()} keeps a repeated key as the configuration's key strategy says. */
final class SilkJsonParserFactory implements JsonParserFactory {

    private final Config config;

    SilkJsonParserFactory(Config config) {
        this.config = config;
    }

    @Override
    public JsonParser createParser(Reader reader) {
        Objects.requireNonNull(reader, "reader");
        return new SilkJsonParser(Documents.read(reader), reader, config.keyStrategy);
    }

    /** A parser of a byte stream whose encoding is told from its first bytes. */
    @Override
    public JsonParser createParser(InputStream in) {
        Objects.requireNonNull(in, "in");
        return new SilkJsonParser(Documents.read(in), in, config.keyStrategy);
    }

    @Override
    public JsonParser createParser(InputStream in, Charset charset) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(charset, "charset");
        return new SilkJsonParser(Documents.read(in, charset), in, config.keyStrategy);
    }

    /** A parser of a tree, which is written as UTF-8 and parsed. */
    @Override
    public JsonParser createParser(JsonObject object) {
        return of(Objects.requireNonNull(object, "object"));
    }

    @Override
    public JsonParser createParser(JsonArray array) {
        return of(Objects.requireNonNull(array, "array"));
    }

    private JsonParser of(JsonStructure structure) {
        return new SilkJsonParser(Values.toJsonBytes(structure), null, config.keyStrategy);
    }

    @Override
    public Map<String, ?> getConfigInUse() {
        return config.keysInUse();
    }
}
