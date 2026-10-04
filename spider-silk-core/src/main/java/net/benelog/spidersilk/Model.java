package net.benelog.spidersilk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * The entries a template renders with, built in one expression, with room for a
 * null value.
 *
 * <pre>{@code
 * app.get("/decks/{deckId}", req -> WebResponse.template("deck",
 *         Model.of("deck", service.deck(req.pathParamLong("deckId")),
 *                 "message", req.flashed("message"))));
 * }</pre>
 *
 * <p>A page model holds a null value routinely: {@link WebRequest#flashed(String)},
 * {@link WebRequest#paramOrNull(String)}, and {@link WebSession#get(String)}
 * answer null when there is nothing to show. {@code Model.of} takes the null,
 * and the template decides what an absent value looks like. {@link Map#of}
 * would throw a {@link NullPointerException} on it, which is why a template
 * takes a {@code Model} and not a map.
 *
 * <p>Each overload mirrors the {@code Map.of} of the same arity, up to ten
 * entries, and keeps the order the entries were given. A null key throws
 * {@link NullPointerException} and a key given twice throws
 * {@link IllegalArgumentException}, as {@code Map.of} does.
 *
 * <p>A model is immutable. One that gains entries conditionally grows with
 * {@link #with(String, Object)}, which answers a new model:
 *
 * <pre>{@code
 * Model model = Model.of("decks", service.decks());
 * if (req.session().get("user") instanceof User user) {
 *     model = model.with("user", user);
 * }
 * return WebResponse.template("decks", model);
 * }</pre>
 */
public final class Model {

    private static final Model EMPTY = new Model(Map.of());

    private final Map<String, @Nullable Object> entries;

    private Model(Map<String, @Nullable Object> entries) {
        this.entries = entries;
    }

    /** An empty model. */
    public static Model of() {
        return build();
    }

    /** A model of one entry. */
    public static Model of(String k1, @Nullable Object v1) {
        return build(k1, v1);
    }

    /** A model of two entries, in that order. */
    public static Model of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2) {
        return build(k1, v1, k2, v2);
    }

    /** A model of three entries, in that order. */
    public static Model of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3) {
        return build(k1, v1, k2, v2, k3, v3);
    }

    /** A model of four entries, in that order. */
    public static Model of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3,
            String k4, @Nullable Object v4) {
        return build(k1, v1, k2, v2, k3, v3, k4, v4);
    }

    /** A model of five entries, in that order. */
    public static Model of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3,
            String k4, @Nullable Object v4, String k5, @Nullable Object v5) {
        return build(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
    }

    /** A model of six entries, in that order. */
    public static Model of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3,
            String k4, @Nullable Object v4, String k5, @Nullable Object v5,
            String k6, @Nullable Object v6) {
        return build(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6);
    }

    /** A model of seven entries, in that order. */
    public static Model of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3,
            String k4, @Nullable Object v4, String k5, @Nullable Object v5,
            String k6, @Nullable Object v6, String k7, @Nullable Object v7) {
        return build(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
    }

    /** A model of eight entries, in that order. */
    public static Model of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3,
            String k4, @Nullable Object v4, String k5, @Nullable Object v5,
            String k6, @Nullable Object v6, String k7, @Nullable Object v7,
            String k8, @Nullable Object v8) {
        return build(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8);
    }

    /** A model of nine entries, in that order. */
    public static Model of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3,
            String k4, @Nullable Object v4, String k5, @Nullable Object v5,
            String k6, @Nullable Object v6, String k7, @Nullable Object v7,
            String k8, @Nullable Object v8, String k9, @Nullable Object v9) {
        return build(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9);
    }

    /** A model of ten entries, in that order. */
    public static Model of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3,
            String k4, @Nullable Object v4, String k5, @Nullable Object v5,
            String k6, @Nullable Object v6, String k7, @Nullable Object v7,
            String k8, @Nullable Object v8, String k9, @Nullable Object v9,
            String k10, @Nullable Object v10) {
        return build(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9,
                k10, v10);
    }

    /**
     * This model with one more entry, as a new model. A null value is an entry.
     *
     * @throws IllegalArgumentException if the model already holds that key,
     *         since a second value under one name is a mistake to report rather
     *         than a value to choose between
     */
    public Model with(String key, @Nullable Object value) {
        Objects.requireNonNull(key, "key");
        if (entries.containsKey(key)) {
            throw new IllegalArgumentException("duplicate key: " + key);
        }
        Map<String, @Nullable Object> copy = new LinkedHashMap<>(entries);
        copy.put(key, value);
        return new Model(Collections.unmodifiableMap(copy));
    }

    /**
     * The entries as a map that cannot be changed, in the order they were
     * given. A {@link TemplateRenderer} hands this to an engine that takes a
     * map, and a test reads it.
     */
    public Map<String, @Nullable Object> asMap() {
        return entries;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof Model model && entries.equals(model.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return "Model" + entries;
    }

    /**
     * The model of those keys and values, which alternate. Every key position
     * holds a {@code String}, since only the overloads above call this.
     */
    private static Model build(@Nullable Object... keysAndValues) {
        if (keysAndValues.length == 0) {
            return EMPTY;
        }
        Map<String, @Nullable Object> model = new LinkedHashMap<>(keysAndValues.length);
        for (int i = 0; i < keysAndValues.length; i += 2) {
            String key = (String) Objects.requireNonNull(keysAndValues[i], "key");
            if (model.containsKey(key)) {
                throw new IllegalArgumentException("duplicate key: " + key);
            }
            model.put(key, keysAndValues[i + 1]);
        }
        return new Model(Collections.unmodifiableMap(model));
    }
}
