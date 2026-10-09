package net.benelog.silkjson.processor;

import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/** Type mirrors as the generated source spells them: qualified, with their type arguments, and without their annotations. */
final class TypeNames {

    private TypeNames() {
    }

    static String of(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE, VOID -> type.getKind().name().toLowerCase(java.util.Locale.ROOT);
            case DECLARED -> declared((DeclaredType) type);
            case ARRAY -> of(((ArrayType) type).getComponentType()) + "[]";
            default -> throw new IllegalArgumentException("A " + type.getKind() + " type cannot be bound: " + type);
        };
    }

    private static String declared(DeclaredType type) {
        StringBuilder name = new StringBuilder(qualifiedName(type));
        List<? extends TypeMirror> arguments = type.getTypeArguments();
        if (!arguments.isEmpty()) {
            name.append('<');
            for (int i = 0; i < arguments.size(); i++) {
                name.append(i > 0 ? ", " : "").append(of(arguments.get(i)));
            }
            name.append('>');
        }
        return name.toString();
    }

    /** The qualified name of the type's class, with a nested class written as its outer class and a dot. */
    static String qualifiedName(DeclaredType type) {
        return ((TypeElement) type.asElement()).getQualifiedName().toString();
    }

    /** Whether the type is that class, by qualified name, whatever its type arguments. */
    static boolean is(TypeMirror type, String qualifiedName) {
        return type.getKind() == TypeKind.DECLARED && qualifiedName.equals(qualifiedName((DeclaredType) type));
    }

    static TypeMirror argument(TypeMirror type, int index) {
        return ((DeclaredType) type).getTypeArguments().get(index);
    }

    static Element element(TypeMirror type) {
        return ((DeclaredType) type).asElement();
    }

    /** The simple names of a nested type joined, so {@code Outer.Inner} generates {@code OuterInnerJson}. */
    static String flatName(TypeElement type) {
        StringBuilder name = new StringBuilder(type.getSimpleName());
        Element enclosing = type.getEnclosingElement();
        while (enclosing instanceof TypeElement outer) {
            name.insert(0, outer.getSimpleName());
            enclosing = outer.getEnclosingElement();
        }
        return name.toString();
    }

    static String packageOf(TypeElement type) {
        Element element = type.getEnclosingElement();
        while (element != null && !(element instanceof javax.lang.model.element.PackageElement)) {
            element = element.getEnclosingElement();
        }
        return element instanceof javax.lang.model.element.PackageElement pkg ? pkg.getQualifiedName().toString() : "";
    }
}
