package net.benelog.spidersilk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A template model built in one expression, with room for a null value.
 *
 * <pre>{@code
 * app.get("/decks/{deckId}", req -> WebResponse.template("deck",
 *         Model.of("deck", service.deck(req.pathParamLong("deckId")),
 *                 "message", req.flashed("message"))));
 * }</pre>
 *
 * <p>{@link Map#of} throws a {@link NullPointerException} on a null value, and
 * a page model holds one routinely: {@link WebRequest#flashed(String)},
 * {@link WebRequest#paramOrNull(String)}, and {@link WebSession#get(String)}
 * answer null when there is nothing to show. With {@code Map.of} that page is
 * a 500, and the alternative is a {@code HashMap} filled one {@code put} at a
 * time. {@code Model.of} takes the null and the template decides what an
 * absent value looks like.
 *
 * <p>Each overload mirrors the {@code Map.of} of the same arity, up to ten
 * entries, and answers a map that cannot be changed and iterates in the order
 * the entries were given. A null key throws {@link NullPointerException} and a
 * key given twice throws {@link IllegalArgumentException}, as {@code Map.of}
 * does. A model that gains entries conditionally is still a mutable map filled
 * by hand, and {@link WebResponse#template(String, Map)} takes either.
 */
public final class Model {

    private Model() {
    }

    /** An empty model. */
    public static Map<String, @Nullable Object> of() {
        return build();
    }

    /** A model of one entry. */
    public static Map<String, @Nullable Object> of(String k1, @Nullable Object v1) {
        return build(k1, v1);
    }

    /** A model of two entries, in that order. */
    public static Map<String, @Nullable Object> of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2) {
        return build(k1, v1, k2, v2);
    }

    /** A model of three entries, in that order. */
    public static Map<String, @Nullable Object> of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3) {
        return build(k1, v1, k2, v2, k3, v3);
    }

    /** A model of four entries, in that order. */
    public static Map<String, @Nullable Object> of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3,
            String k4, @Nullable Object v4) {
        return build(k1, v1, k2, v2, k3, v3, k4, v4);
    }

    /** A model of five entries, in that order. */
    public static Map<String, @Nullable Object> of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3,
            String k4, @Nullable Object v4, String k5, @Nullable Object v5) {
        return build(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
    }

    /** A model of six entries, in that order. */
    public static Map<String, @Nullable Object> of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3,
            String k4, @Nullable Object v4, String k5, @Nullable Object v5,
            String k6, @Nullable Object v6) {
        return build(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6);
    }

    /** A model of seven entries, in that order. */
    public static Map<String, @Nullable Object> of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3,
            String k4, @Nullable Object v4, String k5, @Nullable Object v5,
            String k6, @Nullable Object v6, String k7, @Nullable Object v7) {
        return build(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
    }

    /** A model of eight entries, in that order. */
    public static Map<String, @Nullable Object> of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3,
            String k4, @Nullable Object v4, String k5, @Nullable Object v5,
            String k6, @Nullable Object v6, String k7, @Nullable Object v7,
            String k8, @Nullable Object v8) {
        return build(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8);
    }

    /** A model of nine entries, in that order. */
    public static Map<String, @Nullable Object> of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3,
            String k4, @Nullable Object v4, String k5, @Nullable Object v5,
            String k6, @Nullable Object v6, String k7, @Nullable Object v7,
            String k8, @Nullable Object v8, String k9, @Nullable Object v9) {
        return build(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9);
    }

    /** A model of ten entries, in that order. */
    public static Map<String, @Nullable Object> of(String k1, @Nullable Object v1,
            String k2, @Nullable Object v2, String k3, @Nullable Object v3,
            String k4, @Nullable Object v4, String k5, @Nullable Object v5,
            String k6, @Nullable Object v6, String k7, @Nullable Object v7,
            String k8, @Nullable Object v8, String k9, @Nullable Object v9,
            String k10, @Nullable Object v10) {
        return build(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9,
                k10, v10);
    }

    /**
     * The model of those keys and values, which alternate. Every key position
     * holds a {@code String}, since only the overloads above call this.
     */
    private static Map<String, @Nullable Object> build(@Nullable Object... keysAndValues) {
        Map<String, @Nullable Object> model = new LinkedHashMap<>(keysAndValues.length);
        for (int i = 0; i < keysAndValues.length; i += 2) {
            String key = (String) Objects.requireNonNull(keysAndValues[i], "key");
            if (model.containsKey(key)) {
                throw new IllegalArgumentException("duplicate key: " + key);
            }
            model.put(key, keysAndValues[i + 1]);
        }
        return Collections.unmodifiableMap(model);
    }
}
