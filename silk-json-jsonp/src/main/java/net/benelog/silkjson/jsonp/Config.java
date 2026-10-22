package net.benelog.silkjson.jsonp;

import java.util.Map;

import jakarta.json.JsonConfig;
import jakarta.json.JsonConfig.KeyStrategy;
import jakarta.json.stream.JsonGenerator;

import org.jspecify.annotations.Nullable;

/**
 * The two properties this provider takes, read out of a factory's
 * configuration map: {@link JsonGenerator#PRETTY_PRINTING} for the generator
 * and the writer, and {@link JsonConfig#KEY_STRATEGY} for the reader, the
 * parser's {@code getObject()}, and the object builder. A factory ignores any
 * other property, and its {@code getConfigInUse()} names only the ones it
 * honours.
 */
final class Config {

    static final Config DEFAULT = new Config(false, null);

    final boolean prettyPrinting;
    final KeyStrategy keyStrategy;
    private final @Nullable KeyStrategy keyStrategyGiven;

    private Config(boolean prettyPrinting, @Nullable KeyStrategy keyStrategyGiven) {
        this.prettyPrinting = prettyPrinting;
        this.keyStrategyGiven = keyStrategyGiven;
        this.keyStrategy = keyStrategyGiven == null ? KeyStrategy.LAST : keyStrategyGiven;
    }

    /**
     * The properties of a map, which may be null. Pretty printing is on for
     * any value but {@code false}, as JSON-P lets the value be anything. A
     * key strategy is a {@link KeyStrategy} or its name.
     *
     * @throws IllegalArgumentException for a key strategy that is neither
     */
    static Config of(@Nullable Map<String, ?> config) {
        if (config == null || config.isEmpty()) {
            return DEFAULT;
        }
        Object printing = config.get(JsonGenerator.PRETTY_PRINTING);
        boolean prettyPrinting = config.containsKey(JsonGenerator.PRETTY_PRINTING)
                && !Boolean.FALSE.equals(printing) && !"false".equalsIgnoreCase(String.valueOf(printing));
        return new Config(prettyPrinting, keyStrategy(config.get(JsonConfig.KEY_STRATEGY)));
    }

    private static @Nullable KeyStrategy keyStrategy(@Nullable Object value) {
        if (value == null || value instanceof KeyStrategy) {
            return (KeyStrategy) value;
        }
        if (value instanceof String name) {
            for (KeyStrategy strategy : KeyStrategy.values()) {
                if (strategy.name().equalsIgnoreCase(name)) {
                    return strategy;
                }
            }
        }
        throw new IllegalArgumentException(JsonConfig.KEY_STRATEGY + " is FIRST, LAST, or NONE, not " + value);
    }

    /** What a generator or a writer factory honours. */
    Map<String, ?> printingInUse() {
        return prettyPrinting ? Map.of(JsonGenerator.PRETTY_PRINTING, true) : Map.of();
    }

    /** What a reader, a parser, or a builder factory honours. */
    Map<String, ?> keysInUse() {
        KeyStrategy given = keyStrategyGiven;
        return given == null ? Map.of() : Map.of(JsonConfig.KEY_STRATEGY, given);
    }
}
