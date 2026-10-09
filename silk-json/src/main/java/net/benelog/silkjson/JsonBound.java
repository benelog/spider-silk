package net.benelog.silkjson;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type whose {@link JsonCodec} is generated at compile time by
 * {@code silk-json-processor}, as {@code <Type>Json.CODEC} in the same
 * package. The properties are the record's components, or a class's public
 * fields and accessors, named and shaped by the standard
 * {@code jakarta.json.bind.annotation} annotations: {@code @JsonbProperty},
 * {@code @JsonbTransient}, {@code @JsonbNillable}, {@code @JsonbPropertyOrder},
 * {@code @JsonbCreator}, {@code @JsonbDateFormat}, and {@code @JsonbTypeAdapter}.
 *
 * <pre>{@code
 * @JsonBound
 * public record Deck(long id, @JsonbProperty("deck_name") String name, @Nullable String note) {
 * }
 *
 * app.get("/api/decks/{id}", req -> WebResponse.json(service.deck(id), DeckJson.CODEC));
 * app.post("/api/decks", req -> service.create(req.bodyJson(DeckJson.CODEC)));
 * }</pre>
 *
 * <p>The generated code calls the type's own accessors and constructor: no
 * reflection, no registry, and nothing for a native image to be told. The
 * codec is a constant the handler names, the same as a hand-written writer.
 *
 * <p>A type the application does not own, or one that should not carry the
 * annotations itself, is bound through a mixin: a type annotated
 * {@code @JsonBound(Other.class)}, whose members of the same names carry the
 * annotations for {@code Other}'s properties. The codec is then generated
 * beside the mixin, in its package.
 *
 * <p>On the way in, a component or a creator parameter is required unless its
 * type is annotated {@code @Nullable} or is an {@code Optional}: a missing or
 * null value for any other is a {@link JsonException}, which Spider Silk's
 * {@code req.bodyJson(codec)} answers with 400. A property set through a
 * setter keeps the class's own default when the document leaves it out.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface JsonBound {

    /**
     * The type the codec is for, when the annotated type is a mixin that
     * names and annotates that type's properties rather than the type itself.
     */
    Class<?> value() default Void.class;
}
