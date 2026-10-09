package net.benelog.spidersilk.json.processor;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

import org.jspecify.annotations.Nullable;

/** One property of a bound type: where its value is read from, how it is set, and what the annotations say about it. */
final class Property {

    /** The Java name: a record component, a field, or the name a getter and setter share. */
    final String name;

    /** The type as declared, on the component, the field, the getter, or the creator parameter. */
    final TypeMirror type;

    /** The element the annotations were read from first, for a compile error to point at. */
    final Element element;

    String jsonName;

    /** An expression reading the value off {@code value}, or null for a property that is only read in. */
    @Nullable String getter;

    /** The parameter index in the creator, or -1 when the property is set another way. */
    int creatorIndex = -1;

    /** The setter's name, when the property is set through one. */
    @Nullable String setter;

    /** Whether the property is a public field, assigned directly. */
    boolean field;

    boolean nullable;
    boolean nillable;

    /** Marked {@code @JsonbTransient}: neither written nor read, and handed to a creator as null. */
    boolean excluded;

    @Nullable String dateFormat;
    @Nullable String dateLocale;

    /** A {@code @JsonbTypeAdapter}, with the type its adapted side has on the wire. */
    @Nullable TypeElement adapter;
    @Nullable TypeMirror adapted;

    Property(String name, TypeMirror type, Element element) {
        this.name = name;
        this.type = type;
        this.element = element;
        this.jsonName = name;
    }

    /** Whether the document must hold the property: a creator parameter or a component that is neither nullable nor optional. */
    boolean required() {
        return creatorIndex >= 0 && !nullable && !TypeNames.is(type, "java.util.Optional")
                && !TypeNames.is(type, "java.util.OptionalInt") && !TypeNames.is(type, "java.util.OptionalLong")
                && !TypeNames.is(type, "java.util.OptionalDouble");
    }

    boolean readable() {
        return !excluded && (creatorIndex >= 0 || setter != null || field);
    }

    boolean writable() {
        return !excluded && getter != null;
    }
}
