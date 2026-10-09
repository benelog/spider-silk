package net.benelog.spidersilk.json.processor;

import java.util.List;
import java.util.Map;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

import org.jspecify.annotations.Nullable;

/**
 * The annotations the processor reads, found by their qualified names so that
 * neither the processor nor the application has to have the annotation's
 * class on the processor's classpath: {@code jakarta.json.bind}'s stay the
 * application's own dependency.
 */
final class Annotations {

    static final String JSON_BOUND = "net.benelog.spidersilk.json.JsonBound";
    static final String JSONB_PROPERTY = "jakarta.json.bind.annotation.JsonbProperty";
    static final String JSONB_TRANSIENT = "jakarta.json.bind.annotation.JsonbTransient";
    static final String JSONB_NILLABLE = "jakarta.json.bind.annotation.JsonbNillable";
    static final String JSONB_PROPERTY_ORDER = "jakarta.json.bind.annotation.JsonbPropertyOrder";
    static final String JSONB_CREATOR = "jakarta.json.bind.annotation.JsonbCreator";
    static final String JSONB_DATE_FORMAT = "jakarta.json.bind.annotation.JsonbDateFormat";
    static final String JSONB_NUMBER_FORMAT = "jakarta.json.bind.annotation.JsonbNumberFormat";
    static final String JSONB_TYPE_ADAPTER = "jakarta.json.bind.annotation.JsonbTypeAdapter";
    static final String JSONB_TYPE_SERIALIZER = "jakarta.json.bind.annotation.JsonbTypeSerializer";
    static final String JSONB_TYPE_DESERIALIZER = "jakarta.json.bind.annotation.JsonbTypeDeserializer";
    static final String JSONB_VISIBILITY = "jakarta.json.bind.annotation.JsonbVisibility";
    static final String JSONB_TYPE_INFO = "jakarta.json.bind.annotation.JsonbTypeInfo";
    static final String JSONB_ADAPTER = "jakarta.json.bind.adapter.JsonbAdapter";

    private Annotations() {
    }

    /** The annotation of that qualified name on the element, or null. */
    static @Nullable AnnotationMirror find(Element element, String qualifiedName) {
        return find(element.getAnnotationMirrors(), qualifiedName);
    }

    /** The annotation of that qualified name on the type use, or null. */
    static @Nullable AnnotationMirror findOnType(TypeMirror type, String qualifiedName) {
        return find(type.getAnnotationMirrors(), qualifiedName);
    }

    private static @Nullable AnnotationMirror find(List<? extends AnnotationMirror> mirrors, String qualifiedName) {
        for (AnnotationMirror mirror : mirrors) {
            if (qualifiedName.equals(qualifiedName(mirror))) {
                return mirror;
            }
        }
        return null;
    }

    static boolean has(Element element, String qualifiedName) {
        return find(element, qualifiedName) != null;
    }

    static String qualifiedName(AnnotationMirror mirror) {
        return ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().toString();
    }

    /** The value of that member, as the mirror holds it, or null when the annotation leaves it at its default. */
    static @Nullable Object value(AnnotationMirror mirror, String member) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : mirror.getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(member)) {
                return entry.getValue().getValue();
            }
        }
        return null;
    }

    static @Nullable String string(AnnotationMirror mirror, String member) {
        Object value = value(mirror, member);
        return value instanceof String s ? s : null;
    }

    /**
     * Whether the element or its type says the value may be null: any
     * annotation named {@code Nullable}, whichever library it comes from,
     * on the declaration or on the type.
     */
    static boolean isNullable(List<? extends Element> declarations, TypeMirror type) {
        for (Element declaration : declarations) {
            for (AnnotationMirror mirror : declaration.getAnnotationMirrors()) {
                if (isNullableName(mirror)) {
                    return true;
                }
            }
        }
        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            if (isNullableName(mirror)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNullableName(AnnotationMirror mirror) {
        String simple = mirror.getAnnotationType().asElement().getSimpleName().toString();
        return simple.equals("Nullable") || simple.equals("CheckForNull");
    }
}
