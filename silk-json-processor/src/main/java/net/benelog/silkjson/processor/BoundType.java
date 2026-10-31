package net.benelog.silkjson.processor;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

import org.jspecify.annotations.Nullable;

/**
 * A type to generate a codec for, read off the type's own declaration and the
 * annotations on it: its properties in the order they go out, and the way a
 * value is created on the way in.
 */
final class BoundType {

    /** How a value is created from what was read. */
    enum Creation {
        /** The record's canonical constructor, or the constructor {@code @JsonbCreator} marks. */
        CONSTRUCTOR,
        /** The static method {@code @JsonbCreator} marks. */
        FACTORY,
        /** A public no-argument constructor, then setters and public fields. */
        SETTERS
    }

    /** A subtype {@code @JsonbTypeInfo} lists, with the name it goes by on the wire. */
    record Subtype(String alias, TypeElement type) {
    }

    final TypeElement target;
    final @Nullable TypeElement mixin;
    final String packageName;
    final String generatedName;
    final List<Property> properties;
    final Creation creation;

    /** The creator's parameters, in order, for CONSTRUCTOR and FACTORY. */
    final List<Property> creatorParameters;
    final String factoryName;

    /** The conversions the type takes as a whole, in place of its properties in that direction, or null without any. */
    final @Nullable Property whole;

    /** The key {@code @JsonbTypeInfo} names, and the subtypes it lists, a subclass before its superclass; none without it. */
    final String typeKey;
    final List<Subtype> subtypes;

    private BoundType(TypeElement target, @Nullable TypeElement mixin, String packageName, List<Property> properties,
            Creation creation, List<Property> creatorParameters, String factoryName, @Nullable Property whole,
            String typeKey, List<Subtype> subtypes) {
        this.target = target;
        this.mixin = mixin;
        this.packageName = packageName;
        this.generatedName = TypeNames.flatName(target) + "Json";
        this.properties = properties;
        this.creation = creation;
        this.creatorParameters = creatorParameters;
        this.factoryName = factoryName;
        this.whole = whole;
        this.typeKey = typeKey;
        this.subtypes = subtypes;
    }

    String generatedQualifiedName() {
        return packageName.isEmpty() ? generatedName : packageName + "." + generatedName;
    }

    String targetName() {
        return target.getQualifiedName().toString();
    }

    /** The target as generated source spells it, with its own type variables. */
    String targetType() {
        return targetName() + typeArguments();
    }

    boolean generic() {
        return !target.getTypeParameters().isEmpty();
    }

    /** Whether no value is of this very type: an interface or an abstract class, whose values are all a subtype's. */
    boolean abstractType() {
        return target.getKind() == ElementKind.INTERFACE || target.getModifiers().contains(Modifier.ABSTRACT);
    }

    /** The type's variables, as {@code <K, V>}, or nothing for a type without any. */
    String typeArguments() {
        return generic() ? "<" + String.join(", ", typeVariables()) + ">" : "";
    }

    List<String> typeVariables() {
        return target.getTypeParameters().stream().map(parameter -> parameter.getSimpleName().toString()).toList();
    }

    /** The type's variables with their bounds, as {@code <K extends Comparable<K>, V>}, for the codec to declare the same way. */
    String typeParameters() {
        if (!generic()) {
            return "";
        }
        List<String> declared = new ArrayList<>();
        for (TypeParameterElement parameter : target.getTypeParameters()) {
            List<String> bounds = parameter.getBounds().stream().filter(bound -> !TypeNames.is(bound, "java.lang.Object"))
                    .map(TypeNames::of).toList();
            declared.add(parameter.getSimpleName() + (bounds.isEmpty() ? "" : " extends " + String.join(" & ", bounds)));
        }
        return "<" + String.join(", ", declared) + ">";
    }

    static BoundType of(TypeElement annotated, ProcessingEnvironment env, boolean explicitNames) {
        AnnotationMirror bound = Annotations.find(annotated, Annotations.JSON_BOUND);
        if (bound == null) {
            throw new Invalid(annotated, "Not annotated @JsonBound");
        }
        TypeElement target = annotated;
        TypeElement mixin = null;
        Object value = Annotations.value(bound, "value");
        if (value instanceof DeclaredType declared && declared.getKind() == TypeKind.ERROR) {
            throw new Deferred(declared.toString());
        }
        if (value instanceof DeclaredType declared && !TypeNames.is(declared, "java.lang.Void")) {
            target = (TypeElement) declared.asElement();
            mixin = annotated;
        }
        List<Element> own = mixin != null ? List.of(mixin, target) : List.of(target);
        Property whole = whole(annotated, target, own, env);
        boolean wholeBothWays = whole != null && (whole.adapter != null || (whole.serializer != null && whole.deserializer != null));
        AnnotationMirror typeInfo = Extraction.first(own, Annotations.JSONB_TYPE_INFO);
        boolean takesAbstract = typeInfo != null || wholeBothWays;
        if (target.getKind() != ElementKind.RECORD && target.getKind() != ElementKind.CLASS
                && !(target.getKind() == ElementKind.INTERFACE && takesAbstract)) {
            throw new Invalid(annotated, "@JsonBound binds a record or a class, not " + kindName(target.getKind()) + " "
                    + target.getQualifiedName() + ", unless @JsonbTypeInfo lists its subtypes");
        }
        if (target.getKind() == ElementKind.CLASS && target.getModifiers().contains(Modifier.ABSTRACT) && !takesAbstract) {
            throw new Invalid(annotated, "An abstract class cannot be created from a document: " + target.getQualifiedName());
        }
        if (typeInfo != null && whole != null) {
            throw new Invalid(annotated, "@JsonbTypeInfo chooses a subtype's codec, so it does not go with a conversion of"
                    + " the whole value: " + target.getQualifiedName());
        }
        if (typeInfo != null && !target.getTypeParameters().isEmpty()) {
            throw new Invalid(annotated, "A generic type cannot carry @JsonbTypeInfo: " + target.getQualifiedName());
        }
        String packageName = TypeNames.packageOf(annotated);
        requireReachable(annotated, target, packageName);
        for (Element carrier : carriers(target, mixin, env)) {
            if (Annotations.has(carrier, Annotations.JSONB_VISIBILITY)) {
                String where = carrier instanceof PackageElement pkg ? "package " + pkg.getQualifiedName()
                        : ((TypeElement) carrier).getQualifiedName().toString();
                throw new Invalid(annotated, "@" + simpleName(Annotations.JSONB_VISIBILITY)
                        + " is not supported by the generated codec, here on " + where);
            }
        }
        String typeKey = typeInfo != null ? typeKey(typeInfo) : "";
        List<Subtype> subtypes = typeInfo != null ? subtypes(annotated, target, packageName, typeInfo, typeKey, env) : List.of();

        boolean abstractType = target.getKind() == ElementKind.INTERFACE || target.getModifiers().contains(Modifier.ABSTRACT);
        if (wholeBothWays || abstractType) {
            return new BoundType(target, mixin, packageName, List.of(), Creation.SETTERS, List.of(), "", whole, typeKey, subtypes);
        }
        Extraction extraction = new Extraction(env, target, mixin, explicitNames);
        extraction.run();
        List<Property> ordered = extraction.ordered();
        for (Property property : ordered) {
            requireBindable(property.type, property.element, target);
            TypeMirror adapted = property.adapted;
            if (adapted != null) {
                requireBindable(adapted, property.element, target);
            }
        }
        return new BoundType(target, mixin, packageName, ordered, extraction.creation,
                extraction.creatorParameters, extraction.factoryName, whole, typeKey, subtypes);
    }

    /** The key a {@code @JsonbTypeInfo} names, JSON-B's {@code @type} when it names none. */
    private static String typeKey(AnnotationMirror typeInfo) {
        String key = Annotations.string(typeInfo, "key");
        return key != null ? key : "@type";
    }

    /** The subtypes a {@code @JsonbTypeInfo} lists, checked here rather than in code that would not compile. */
    private static List<Subtype> subtypes(TypeElement annotated, TypeElement target, String packageName,
            AnnotationMirror typeInfo, String typeKey, ProcessingEnvironment env) {
        Types types = env.getTypeUtils();
        List<Subtype> subtypes = new ArrayList<>();
        Set<String> aliases = new HashSet<>();
        Object listed = Annotations.value(typeInfo, "value");
        for (Object entry : listed instanceof List<?> values ? values : List.of()) {
            AnnotationMirror subtype = (AnnotationMirror) ((AnnotationValue) entry).getValue();
            String alias = String.valueOf(Annotations.string(subtype, "alias"));
            Object named = Annotations.value(subtype, "type");
            if (!(named instanceof DeclaredType declared) || declared.getKind() == TypeKind.ERROR) {
                throw new Deferred(String.valueOf(named));
            }
            TypeElement element = (TypeElement) declared.asElement();
            String name = element.getQualifiedName().toString();
            if (element.equals(target) || !types.isSubtype(types.erasure(declared), types.erasure(target.asType()))) {
                throw new Invalid(annotated, name + " is not a subtype of " + target.getQualifiedName());
            }
            if (!Annotations.has(element, Annotations.JSON_BOUND)) {
                throw new Invalid(annotated, "The subtype " + name + " needs @JsonBound, since its codec reads and writes it");
            }
            if (!element.getTypeParameters().isEmpty()) {
                throw new Invalid(annotated, "The subtype " + name + " is generic, so no one codec binds it");
            }
            if (Annotations.has(element, Annotations.JSONB_TYPE_ADAPTER) || Annotations.has(element, Annotations.JSONB_TYPE_SERIALIZER)) {
                throw new Invalid(annotated, "The subtype " + name + " is written whole by a conversion, which leaves no place for the key "
                        + typeKey);
            }
            AnnotationMirror chained = Annotations.find(element, Annotations.JSONB_TYPE_INFO);
            if (chained != null && typeKey(chained).equals(typeKey)) {
                throw new Invalid(annotated, "The subtype " + name + " takes the key " + typeKey + " for its own subtypes too");
            }
            if (!aliases.add(alias)) {
                throw new Invalid(annotated, "Two subtypes of " + target.getQualifiedName() + " go by the alias " + alias);
            }
            requireReachable(annotated, element, packageName);
            int place = subtypes.size();
            for (int i = subtypes.size() - 1; i >= 0; i--) {
                if (types.isSubtype(types.erasure(declared), types.erasure(subtypes.get(i).type().asType()))) {
                    place = i;
                }
            }
            subtypes.add(place, new Subtype(alias, element));
        }
        if (subtypes.isEmpty()) {
            throw new Invalid(annotated, "@JsonbTypeInfo on " + target.getQualifiedName() + " lists no subtypes");
        }
        return subtypes;
    }

    /** What the type's own {@code @JsonbTypeAdapter}, serializer, and deserializer convert, as a property named for no member. */
    private static @Nullable Property whole(TypeElement annotated, TypeElement target, List<Element> own, ProcessingEnvironment env) {
        AnnotationMirror adapter = Extraction.first(own, Annotations.JSONB_TYPE_ADAPTER);
        AnnotationMirror serializer = Extraction.first(own, Annotations.JSONB_TYPE_SERIALIZER);
        AnnotationMirror deserializer = Extraction.first(own, Annotations.JSONB_TYPE_DESERIALIZER);
        if (adapter == null && serializer == null && deserializer == null) {
            return null;
        }
        Property whole = new Property("$type", target.asType(), annotated);
        conversions(whole, adapter, serializer, deserializer, env);
        return whole;
    }

    /** A kind of class that converts a value, as JSON-B declares it: its annotation is checked the same way for each. */
    private record Contract(String noun, String verb, String qualifiedName, int typeArguments, boolean boxes) {
    }

    private static final Contract ADAPTER = new Contract("adapter", "adapts", Annotations.JSONB_ADAPTER, 2, false);
    private static final Contract SERIALIZER = new Contract("serializer", "serializes", Annotations.JSONB_SERIALIZER, 1, true);
    private static final Contract DESERIALIZER = new Contract("deserializer", "deserializes", Annotations.JSONB_DESERIALIZER, 1, true);

    /** Reads a property's conversions, each created with {@code new}, and checks them against its type. */
    private static void conversions(Property property, @Nullable AnnotationMirror adapter, @Nullable AnnotationMirror serializer,
            @Nullable AnnotationMirror deserializer, ProcessingEnvironment env) {
        if (adapter != null && (serializer != null || deserializer != null)) {
            throw new Invalid(property.element, "A value takes a @JsonbTypeAdapter or a serializer and deserializer, not both");
        }
        if (adapter != null) {
            DeclaredType adapterType = converter(property, adapter, ADAPTER, env);
            property.adapter = (TypeElement) adapterType.asElement();
            property.adapted = contractOf(adapterType, ADAPTER, env).getTypeArguments().get(1);
        }
        if (serializer != null) {
            requireJsonbModule(property, env);
            property.serializer = (TypeElement) converter(property, serializer, SERIALIZER, env).asElement();
        }
        if (deserializer != null) {
            requireJsonbModule(property, env);
            property.deserializer = (TypeElement) converter(property, deserializer, DESERIALIZER, env).asElement();
            property.deserializedClass = TypeNames.of(env.getTypeUtils().erasure(property.type)) + ".class";
        }
    }

    private static void requireJsonbModule(Property property, ProcessingEnvironment env) {
        if (env.getElementUtils().getTypeElement(Annotations.JSONB_CODECS) == null) {
            throw new Invalid(property.element, "A JSON-B serializer or deserializer runs through silk-json-jsonb:"
                    + " add it to the compile classpath");
        }
    }

    /** The class an annotation names, once it is checked: public, with a public no-argument constructor, and for the property's type. */
    private static DeclaredType converter(Property property, AnnotationMirror mirror, Contract contract, ProcessingEnvironment env) {
        Types types = env.getTypeUtils();
        Object value = Annotations.value(mirror, "value");
        if (!(value instanceof DeclaredType type)) {
            throw new Invalid(property.element, "The annotation names no " + contract.noun() + " class");
        }
        if (type.getKind() == TypeKind.ERROR) {
            throw new Deferred(type.toString());
        }
        TypeElement converter = (TypeElement) type.asElement();
        boolean constructible = false;
        for (ExecutableElement constructor : ElementFilter.constructorsIn(converter.getEnclosedElements())) {
            if (constructor.getParameters().isEmpty() && constructor.getModifiers().contains(Modifier.PUBLIC)) {
                constructible = true;
            }
        }
        if (!constructible || !converter.getModifiers().contains(Modifier.PUBLIC)) {
            throw new Invalid(property.element, "The " + contract.noun() + " " + converter.getQualifiedName()
                    + " needs a public no-argument constructor, since the generated codec creates it with new");
        }
        DeclaredType implemented = contractOf(type, contract, env);
        TypeMirror handled = implemented.getTypeArguments().get(0);
        TypeMirror own = contract.boxes() && property.type instanceof PrimitiveType primitive
                ? types.boxedClass(primitive).asType() : property.type;
        if (!types.isSameType(types.erasure(handled), types.erasure(own))) {
            throw new Invalid(property.element, "The " + contract.noun() + " " + converter.getQualifiedName() + " " + contract.verb() + " "
                    + TypeNames.of(handled) + ", not " + TypeNames.of(property.type));
        }
        return type;
    }

    private static DeclaredType contractOf(DeclaredType type, Contract contract, ProcessingEnvironment env) {
        DeclaredType implemented = findContract(type, contract.qualifiedName(), env.getTypeUtils());
        if (implemented == null || implemented.getTypeArguments().size() != contract.typeArguments()) {
            throw new Invalid(type.asElement(), type + " does not implement " + simpleName(contract.qualifiedName())
                    + (contract.typeArguments() == 2 ? "<Original, Adapted>" : "<T>"));
        }
        return implemented;
    }

    private static @Nullable DeclaredType findContract(TypeMirror type, String qualifiedName, Types types) {
        for (TypeMirror supertype : types.directSupertypes(type)) {
            if (TypeNames.is(supertype, qualifiedName)) {
                return (DeclaredType) supertype;
            }
            DeclaredType found = findContract(supertype, qualifiedName, types);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The elements whose type-level annotations apply to the target: the mixin and its package first, then the target and its package. */
    static List<Element> carriers(TypeElement target, @Nullable TypeElement mixin, ProcessingEnvironment env) {
        List<Element> carriers = new ArrayList<>();
        if (mixin != null) {
            carriers.add(mixin);
            carriers.add(env.getElementUtils().getPackageOf(mixin));
        }
        carriers.add(target);
        PackageElement targetPackage = env.getElementUtils().getPackageOf(target);
        if (!carriers.contains(targetPackage)) {
            carriers.add(targetPackage);
        }
        return carriers;
    }

    /**
     * The checks that the generated code can name the target and create it:
     * a nested class is static, and neither the target nor any type it is
     * nested in is private, or other than public when the codec sits in
     * another package.
     */
    private static void requireReachable(TypeElement annotated, TypeElement target, String packageName) {
        if (target.getNestingKind() == NestingKind.MEMBER && target.getKind() == ElementKind.CLASS
                && !target.getModifiers().contains(Modifier.STATIC)) {
            throw new Invalid(annotated, target.getQualifiedName() + " is an inner class, which needs an instance of "
                    + String.valueOf(target.getEnclosingElement()) + " to be created: declare it static");
        }
        if (target.getNestingKind() == NestingKind.LOCAL || target.getNestingKind() == NestingKind.ANONYMOUS) {
            throw new Invalid(annotated, "A local class cannot be bound, since a codec cannot name it");
        }
        boolean samePackage = packageName.equals(TypeNames.packageOf(target));
        for (Element element = target; element instanceof TypeElement type; element = element.getEnclosingElement()) {
            if (type.getModifiers().contains(Modifier.PRIVATE)) {
                throw new Invalid(annotated, type.getQualifiedName() + " is private, so a codec cannot reach "
                        + target.getQualifiedName());
            }
            if (!samePackage && !type.getModifiers().contains(Modifier.PUBLIC)) {
                throw new Invalid(annotated, type.getQualifiedName() + " is not public, so a codec in " + packageName
                        + " cannot reach " + target.getQualifiedName());
            }
        }
    }

    /**
     * The check that a property's type is one the generated source can spell:
     * no wildcard, raw type, or type variable other than the target's own. A
     * type javac has not resolved defers the whole codec to a later round.
     */
    static void requireBindable(TypeMirror type, Element at, TypeElement target) {
        switch (type.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> {
                // spelled as they are
            }
            case ERROR -> throw new Deferred(type.toString());
            case ARRAY -> requireBindable(((ArrayType) type).getComponentType(), at, target);
            case DECLARED -> {
                DeclaredType declared = (DeclaredType) type;
                TypeElement element = (TypeElement) declared.asElement();
                if (declared.getTypeArguments().isEmpty() && !element.getTypeParameters().isEmpty()) {
                    throw new Invalid(at, "The raw type " + element.getQualifiedName() + " cannot be bound: give its type arguments");
                }
                for (TypeMirror argument : declared.getTypeArguments()) {
                    requireBindable(argument, at, target);
                }
            }
            case WILDCARD -> throw new Invalid(at, "The wildcard " + type + " cannot be bound: name the type itself");
            case TYPEVAR -> {
                if (!target.getTypeParameters().contains(((TypeVariable) type).asElement())) {
                    throw new Invalid(at, "The type variable " + type + " cannot be bound: name the type itself");
                }
            }
            default -> throw new Invalid(at, "The type " + type + " cannot be bound");
        }
    }

    private static String kindName(ElementKind kind) {
        return kind.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    static String simpleName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    /** The walk over the target's members, and the mixin's, that fills in the properties. */
    private static final class Extraction {

        private final ProcessingEnvironment env;
        private final Types types;
        private final TypeElement target;
        private final @Nullable TypeElement mixin;
        private final boolean explicitNames;

        private final Map<String, Property> properties = new LinkedHashMap<>();
        private final Map<String, List<Element>> declarations = new LinkedHashMap<>();
        private Creation creation = Creation.SETTERS;
        private List<Property> creatorParameters = List.of();
        private String factoryName = "";
        private boolean typeNillable;
        private @Nullable AnnotationMirror typeDateFormat;
        private @Nullable AnnotationMirror typeNumberFormat;

        Extraction(ProcessingEnvironment env, TypeElement target, @Nullable TypeElement mixin, boolean explicitNames) {
            this.env = env;
            this.types = env.getTypeUtils();
            this.target = target;
            this.mixin = mixin;
            this.explicitNames = explicitNames;
        }

        void run() {
            // The type's say comes before its package's, and a mixin's before the target's.
            List<Element> defaults = new ArrayList<>();
            if (mixin != null) {
                defaults.add(mixin);
            }
            defaults.add(target);
            if (mixin != null) {
                defaults.add(env.getElementUtils().getPackageOf(mixin));
            }
            defaults.add(env.getElementUtils().getPackageOf(target));
            AnnotationMirror nillable = first(defaults, Annotations.JSONB_NILLABLE);
            typeNillable = nillable != null && !Boolean.FALSE.equals(Annotations.value(nillable, "value"));
            typeDateFormat = first(defaults, Annotations.JSONB_DATE_FORMAT);
            typeNumberFormat = first(defaults, Annotations.JSONB_NUMBER_FORMAT);
            if (target.getKind() == ElementKind.RECORD) {
                record();
            } else {
                beanClass();
            }
            ExecutableElement creator = creator();
            if (creator != null) {
                creator(creator);
            } else if (target.getKind() == ElementKind.RECORD) {
                canonical();
            } else {
                requireNoArgConstructor();
            }
            for (Property property : properties.values()) {
                annotate(property);
            }
        }

        // ---- the members ----

        private void record() {
            Map<String, VariableElement> fields = new LinkedHashMap<>();
            for (VariableElement field : ElementFilter.fieldsIn(target.getEnclosedElements())) {
                fields.put(field.getSimpleName().toString(), field);
            }
            for (RecordComponentElement component : target.getRecordComponents()) {
                String name = component.getSimpleName().toString();
                ExecutableElement accessor = component.getAccessor();
                Property property = new Property(name, accessor.getReturnType(), component);
                property.getter = "$value." + name + "()";
                properties.put(name, property);
                List<Element> declared = new ArrayList<>();
                declared.add(component);
                declared.add(accessor);
                VariableElement field = fields.get(name);
                if (field != null) {
                    declared.add(field);
                }
                declarations.put(name, declared);
            }
        }

        /**
         * One walk over the members of the class and of each superclass up to
         * {@code Object}, the topmost first and each in declaration order: a
         * property's place is where its name first appears, a private field
         * included. A subclass's member overrides or hides its superclass's,
         * and its annotations are read first.
         */
        private void beanClass() {
            List<TypeElement> chain = new ArrayList<>();
            for (TypeElement type = target; type != null; type = superclass(type)) {
                chain.add(0, type);
            }
            DeclaredType owner = (DeclaredType) target.asType();
            Map<String, VariableElement> fields = new LinkedHashMap<>();
            Map<String, ExecutableElement> getters = new LinkedHashMap<>();
            Map<String, List<ExecutableElement>> setters = new LinkedHashMap<>();
            for (TypeElement declaring : chain) {
                Map<String, List<Element>> own = new LinkedHashMap<>();
                for (Element member : declaring.getEnclosedElements()) {
                    if (member.getModifiers().contains(Modifier.STATIC)) {
                        continue;
                    }
                    if (member instanceof VariableElement field && member.getKind() == ElementKind.FIELD) {
                        if (field.getModifiers().contains(Modifier.TRANSIENT)) {
                            continue;
                        }
                        String name = field.getSimpleName().toString();
                        own.computeIfAbsent(name, n -> new ArrayList<>()).add(field);
                        if (field.getModifiers().contains(Modifier.PUBLIC)) {
                            fields.put(name, field);
                        } else {
                            fields.remove(name); // a field the subclass hides with one the codec cannot reach
                        }
                    } else if (member instanceof ExecutableElement method && member.getKind() == ElementKind.METHOD
                            && method.getModifiers().contains(Modifier.PUBLIC)) {
                        String methodName = method.getSimpleName().toString();
                        String name = propertyName(methodName, method);
                        if (name == null) {
                            continue;
                        }
                        own.computeIfAbsent(name, n -> new ArrayList<>()).add(method);
                        if (method.getParameters().isEmpty() && method.getReturnType().getKind() != TypeKind.VOID
                                && !methodName.startsWith("set")) {
                            getters.put(name, method);
                        } else if (method.getParameters().size() == 1 && methodName.startsWith("set")) {
                            List<ExecutableElement> named = setters.computeIfAbsent(name, n -> new ArrayList<>());
                            TypeMirror parameter = types.erasure(method.getParameters().get(0).asType());
                            named.removeIf(other -> types.isSameType(types.erasure(other.getParameters().get(0).asType()), parameter));
                            named.add(method);
                        }
                    }
                }
                for (Map.Entry<String, List<Element>> entry : own.entrySet()) {
                    List<Element> declared = declarations.computeIfAbsent(entry.getKey(), n -> new ArrayList<>());
                    declared.addAll(0, entry.getValue());
                }
            }
            for (String name : declarations.keySet()) {
                beanProperty(name, owner, fields.get(name), getters.get(name), setters.getOrDefault(name, List.of()));
            }
        }

        private @Nullable TypeElement superclass(TypeElement type) {
            TypeMirror superclass = type.getSuperclass();
            if (superclass.getKind() == TypeKind.ERROR) {
                throw new Deferred(superclass.toString());
            }
            if (superclass.getKind() != TypeKind.DECLARED) {
                return null;
            }
            TypeElement element = (TypeElement) ((DeclaredType) superclass).asElement();
            return element.getQualifiedName().contentEquals("java.lang.Object") ? null : element;
        }

        /**
         * The property of a field, a getter, and setters of one name, typed as
         * members of the target, so a superclass's type variables read as the
         * target binds them. The getter's type wins, and a field or a setter of
         * another type is a compile error rather than generated code that does
         * not compile.
         */
        private void beanProperty(String name, DeclaredType owner, @Nullable VariableElement field,
                @Nullable ExecutableElement getter, List<ExecutableElement> setters) {
            TypeMirror fieldType = field != null ? types.asMemberOf(owner, field) : null;
            TypeMirror getterType = getter != null ? ((ExecutableType) types.asMemberOf(owner, getter)).getReturnType() : null;
            TypeMirror type = getterType != null ? getterType : fieldType;
            ExecutableElement setter = null;
            for (ExecutableElement candidate : setters) {
                TypeMirror parameter = ((ExecutableType) types.asMemberOf(owner, candidate)).getParameterTypes().get(0);
                if (type == null && setters.size() > 1) {
                    throw new Invalid(candidate, "The property " + name + " has more than one setter and no getter to choose by");
                }
                if (type == null || types.isSameType(type, parameter)) {
                    setter = candidate;
                    type = parameter;
                }
            }
            if (setter == null && !setters.isEmpty() && type != null) {
                ExecutableElement first = setters.get(0);
                throw new Invalid(first, "The setter " + first.getSimpleName() + " takes a "
                        + first.getParameters().get(0).asType() + " where the property " + name + " is a " + type);
            }
            if (type == null) {
                return; // a private field alone, which is no property
            }
            boolean assignable = field != null && !field.getModifiers().contains(Modifier.FINAL);
            if (field != null && fieldType != null && getterType != null && !types.isSameType(fieldType, getterType)
                    && setter == null && assignable) {
                throw new Invalid(field, "The field " + name + " is a " + fieldType + " where its getter returns a " + getterType);
            }
            @Nullable Element element = getter != null ? getter : field != null ? field : setter;
            for (Element declared : declarations.getOrDefault(name, List.of())) {
                if (declared.equals(field) || declared.equals(getter) || declared.equals(setter)) {
                    element = declared; // the first of them in declaration order, the subclass's first
                    break;
                }
            }
            if (element == null) {
                return;
            }
            Property property = new Property(name, type, element);
            if (getter != null) {
                property.getter = "$value." + getter.getSimpleName() + "()";
            } else if (field != null) {
                property.getter = "$value." + name;
            }
            if (setter != null) {
                property.setter = setter.getSimpleName().toString();
            } else if (assignable && fieldType != null && types.isSameType(fieldType, type)) {
                property.field = true;
            }
            properties.put(name, property);
        }

        /** The property a getter or a setter is for, by JavaBeans convention, or null for a method that is neither. */
        private static @Nullable String propertyName(String methodName, ExecutableElement method) {
            if (methodName.startsWith("get") && methodName.length() > 3 && method.getParameters().isEmpty()) {
                return decapitalize(methodName.substring(3));
            }
            // JavaBeans names a boolean's getter is...; a Boolean's is taken too, as Yasson takes it.
            if (methodName.startsWith("is") && methodName.length() > 2 && method.getParameters().isEmpty()
                    && (method.getReturnType().getKind() == TypeKind.BOOLEAN
                            || TypeNames.is(method.getReturnType(), "java.lang.Boolean"))) {
                return decapitalize(methodName.substring(2));
            }
            if (methodName.startsWith("set") && methodName.length() > 3 && method.getParameters().size() == 1) {
                return decapitalize(methodName.substring(3));
            }
            return null;
        }

        private static String decapitalize(String name) {
            if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
                return name;
            }
            return Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }

        // ---- creation ----

        private @Nullable ExecutableElement creator() {
            ExecutableElement found = null;
            for (ExecutableElement constructor : ElementFilter.constructorsIn(target.getEnclosedElements())) {
                if (Annotations.has(constructor, Annotations.JSONB_CREATOR)) {
                    found = requireOne(found, constructor);
                }
            }
            for (ExecutableElement method : ElementFilter.methodsIn(target.getEnclosedElements())) {
                if (Annotations.has(method, Annotations.JSONB_CREATOR)) {
                    if (!method.getModifiers().contains(Modifier.STATIC)) {
                        throw new Invalid(method, "@JsonbCreator goes on a constructor or a static method");
                    }
                    found = requireOne(found, method);
                }
            }
            return found;
        }

        private ExecutableElement requireOne(@Nullable ExecutableElement found, ExecutableElement another) {
            if (found != null) {
                throw new Invalid(another, "More than one @JsonbCreator in " + target.getQualifiedName());
            }
            if (another.getModifiers().contains(Modifier.PRIVATE)) {
                throw new Invalid(another, "A @JsonbCreator cannot be private, since the generated codec calls it");
            }
            if (!another.getModifiers().contains(Modifier.PUBLIC) && !samePackage()) {
                throw new Invalid(another, "A @JsonbCreator the generated codec can reach is public");
            }
            return another;
        }

        private boolean samePackage() {
            return TypeNames.packageOf(target).equals(TypeNames.packageOf(mixin != null ? mixin : target));
        }

        private void creator(ExecutableElement creator) {
            creation = creator.getKind() == ElementKind.CONSTRUCTOR ? Creation.CONSTRUCTOR : Creation.FACTORY;
            factoryName = creator.getSimpleName().toString();
            List<Property> parameters = new ArrayList<>();
            int index = 0;
            for (VariableElement parameter : creator.getParameters()) {
                AnnotationMirror named = Annotations.find(parameter, Annotations.JSONB_PROPERTY);
                String jsonName = named != null ? Annotations.string(named, "value") : null;
                if (jsonName != null && jsonName.isEmpty()) {
                    jsonName = null;
                }
                // A parameter names its property by its @JsonbProperty, as JSON-B
                // matches them, and by its Java name only without one.
                String name = parameter.getSimpleName().toString();
                Property property = jsonName != null ? byDeclaredJsonName(jsonName) : properties.get(name);
                if (property != null) {
                    name = property.name;
                } else {
                    if (properties.containsKey(name)) {
                        throw new Invalid(parameter, "The creator parameter " + name + " is named \"" + jsonName
                                + "\" in JSON, where the property " + name + " is named \"" + declaredJsonName(name) + "\"");
                    }
                    property = new Property(name, parameter.asType(), parameter);
                    properties.put(name, property);
                }
                if (!types.isSameType(types.erasure(property.type), types.erasure(parameter.asType()))) {
                    throw new Invalid(parameter, "The creator parameter " + name + " is a " + TypeNames.of(parameter.asType())
                            + " where the property is a " + TypeNames.of(property.type));
                }
                property.creatorIndex = index++;
                if (jsonName != null && !jsonName.isEmpty()) {
                    property.jsonName = jsonName;
                }
                declarations.computeIfAbsent(name, n -> new ArrayList<>()).add(parameter);
                parameters.add(property);
            }
            creatorParameters = parameters;
        }

        /** The property whose own declarations, or the mixin's, name it so in JSON, or whose Java name it is when none do. */
        private @Nullable Property byDeclaredJsonName(String jsonName) {
            for (Property property : properties.values()) {
                if (declaredJsonName(property.name).equals(jsonName)) {
                    return property;
                }
            }
            return null;
        }

        private String declaredJsonName(String name) {
            List<Element> declared = new ArrayList<>(mixinMembers(name));
            declared.addAll(declarations.getOrDefault(name, List.of()));
            AnnotationMirror named = first(declared, Annotations.JSONB_PROPERTY);
            String jsonName = named != null ? Annotations.string(named, "value") : null;
            return jsonName != null && !jsonName.isEmpty() ? jsonName : name;
        }

        private void canonical() {
            creation = Creation.CONSTRUCTOR;
            List<Property> parameters = new ArrayList<>();
            int index = 0;
            for (RecordComponentElement component : target.getRecordComponents()) {
                Property property = properties.get(component.getSimpleName().toString());
                if (property == null) {
                    throw new IllegalStateException("No property for component " + component.getSimpleName());
                }
                property.creatorIndex = index++;
                parameters.add(property);
            }
            creatorParameters = parameters;
            for (ExecutableElement constructor : ElementFilter.constructorsIn(target.getEnclosedElements())) {
                if (constructor.getParameters().size() == parameters.size() && !constructor.getModifiers().contains(Modifier.PUBLIC)
                        && (!samePackage() || constructor.getModifiers().contains(Modifier.PRIVATE)) && isCanonical(constructor)) {
                    throw new Invalid(target, "The canonical constructor of " + target.getQualifiedName()
                            + " is not public, so a codec in another package cannot call it");
                }
            }
        }

        private boolean isCanonical(ExecutableElement constructor) {
            List<? extends RecordComponentElement> components = target.getRecordComponents();
            for (int i = 0; i < components.size(); i++) {
                if (!types.isSameType(constructor.getParameters().get(i).asType(), components.get(i).asType())) {
                    return false;
                }
            }
            return true;
        }

        private void requireNoArgConstructor() {
            creation = Creation.SETTERS;
            for (ExecutableElement constructor : ElementFilter.constructorsIn(target.getEnclosedElements())) {
                if (!constructor.getParameters().isEmpty()) {
                    continue;
                }
                if (constructor.getModifiers().contains(Modifier.PUBLIC)
                        || (samePackage() && !constructor.getModifiers().contains(Modifier.PRIVATE))) {
                    return;
                }
                if (constructor.getModifiers().contains(Modifier.PRIVATE)) {
                    throw new Invalid(target, "The no-argument constructor of " + target.getQualifiedName()
                            + " is private, so the generated codec cannot call it: mark a constructor @JsonbCreator, or open it");
                }
            }
            throw new Invalid(target, target.getQualifiedName() + " has no @JsonbCreator and no public no-argument"
                    + " constructor, so a value cannot be created from a document");
        }

        // ---- the annotations on each property ----

        private void annotate(Property property) {
            List<Element> declared = new ArrayList<>(declarations.getOrDefault(property.name, List.of()));
            if (mixin != null) {
                declared.addAll(0, mixinMembers(property.name)); // a mixin's say comes first
            }
            for (Element element : declared) {
                if (Annotations.has(element, Annotations.JSONB_TRANSIENT)) {
                    if (property.creatorIndex >= 0 && property.type.getKind().isPrimitive()) {
                        throw new Invalid(element, "A @JsonbTransient creator parameter cannot be a primitive, since"
                                + " the codec hands it null: " + property.name);
                    }
                    property.excluded = true;
                    return;
                }
            }
            AnnotationMirror named = first(declared, Annotations.JSONB_PROPERTY);
            String jsonName = named != null ? Annotations.string(named, "value") : null;
            if (jsonName != null && !jsonName.isEmpty()) {
                property.jsonName = jsonName;
            } else if (explicitNames && (property.writable() || property.readable())) {
                throw new Invalid(property.element, "The property " + property.name
                        + " has no @JsonbProperty name, and the build asks for one on every property");
            }
            property.nullable = Annotations.isNullable(declared, property.type);
            AnnotationMirror nillable = first(declared, Annotations.JSONB_NILLABLE);
            property.nillable = nillable != null ? !Boolean.FALSE.equals(Annotations.value(nillable, "value")) : typeNillable;
            if (named != null && Boolean.TRUE.equals(Annotations.value(named, "nillable"))) {
                property.nillable = true;
            }
            AnnotationMirror dateFormat = first(declared, Annotations.JSONB_DATE_FORMAT);
            if (dateFormat == null) {
                dateFormat = typeDateFormat;
            }
            if (dateFormat != null) {
                String pattern = Annotations.string(dateFormat, "value");
                String locale = Annotations.string(dateFormat, "locale");
                if (pattern != null && !pattern.equals("##default")) {
                    property.timeInMillis = pattern.equals("##time-in-millis");
                    property.dateFormat = property.timeInMillis ? null : pattern;
                    property.dateLocale = locale != null && !locale.equals("##default") ? locale : null;
                }
            }
            AnnotationMirror numberFormat = first(declared, Annotations.JSONB_NUMBER_FORMAT);
            if (numberFormat == null) {
                numberFormat = typeNumberFormat;
            }
            if (numberFormat != null) {
                numberFormat(property, numberFormat);
            }
            conversions(property, first(declared, Annotations.JSONB_TYPE_ADAPTER), first(declared, Annotations.JSONB_TYPE_SERIALIZER),
                    first(declared, Annotations.JSONB_TYPE_DESERIALIZER), env);
        }

        /** A {@code @JsonbNumberFormat}, whose pattern is tried here so that a bad one is a compile error. No pattern leaves the number plain. */
        private void numberFormat(Property property, AnnotationMirror mirror) {
            String pattern = Annotations.string(mirror, "value");
            if (pattern == null || pattern.isEmpty()) {
                return;
            }
            try {
                new DecimalFormat(pattern);
            } catch (IllegalArgumentException e) {
                throw new Invalid(property.element, "The number format " + pattern + " is not a pattern: " + e.getMessage());
            }
            String locale = Annotations.string(mirror, "locale");
            property.numberFormat = pattern;
            property.numberLocale = locale != null && !locale.equals("##default") ? locale : null;
        }

        private List<Element> mixinMembers(String name) {
            List<Element> members = new ArrayList<>();
            if (mixin == null) {
                return members;
            }
            for (Element member : mixin.getEnclosedElements()) {
                String memberName = member.getSimpleName().toString();
                if (!(member instanceof ExecutableElement) && !(member instanceof VariableElement)) {
                    continue;
                }
                // A record's accessors are named as its components, so a mixin's
                // isAdmin() is the component isAdmin, not a JavaBeans admin.
                String propertyName = member instanceof ExecutableElement method && target.getKind() != ElementKind.RECORD
                        ? propertyNameOrSelf(memberName, method) : memberName;
                if (propertyName.equals(name)) {
                    members.add(member);
                }
            }
            return members;
        }

        private static String propertyNameOrSelf(String methodName, ExecutableElement method) {
            String bean = propertyName(methodName, method);
            return bean != null ? bean : methodName;
        }

        private static @Nullable AnnotationMirror first(List<Element> declared, String qualifiedName) {
            for (Element element : declared) {
                AnnotationMirror mirror = Annotations.find(element, qualifiedName);
                if (mirror != null) {
                    return mirror;
                }
            }
            return null;
        }

        // ---- the order they go out ----

        List<Property> ordered() {
            List<Property> ordered = new ArrayList<>();
            AnnotationMirror order = Annotations.find(target, Annotations.JSONB_PROPERTY_ORDER);
            if (mixin != null && Annotations.find(mixin, Annotations.JSONB_PROPERTY_ORDER) != null) {
                order = Annotations.find(mixin, Annotations.JSONB_PROPERTY_ORDER);
            }
            if (order != null) {
                Object value = Annotations.value(order, "value");
                if (value instanceof List<?> names) {
                    for (Object named : names) {
                        if (!(named instanceof AnnotationValue annotationValue)) {
                            continue;
                        }
                        String name = String.valueOf(annotationValue.getValue());
                        Property property = byJsonOrJavaName(name);
                        if (property == null) {
                            throw new Invalid(target, "@JsonbPropertyOrder names " + name + ", which is not a property of "
                                    + target.getQualifiedName());
                        }
                        if (!ordered.contains(property)) {
                            ordered.add(property);
                        }
                    }
                }
            }
            for (String name : declarations.keySet()) {
                Property property = properties.get(name);
                if (property != null && !ordered.contains(property) && (property.writable() || property.readable())) {
                    ordered.add(property);
                }
            }
            for (Property property : properties.values()) {
                if (!ordered.contains(property) && (property.writable() || property.readable())) {
                    ordered.add(property);
                }
            }
            Map<String, Property> byJsonName = new LinkedHashMap<>();
            for (Property property : ordered) {
                Property other = byJsonName.put(property.jsonName, property);
                if (other != null) {
                    throw new Invalid(property.element, "Two properties are named \"" + property.jsonName + "\" in JSON: "
                            + other.name + " and " + property.name);
                }
            }
            requireTypeKeysFree(target.asType(), byJsonName);
            env.getElementUtils(); // the environment is held for the messages above; nothing else is read from it here
            return ordered;
        }

        /** The check that no property takes the name of a key {@code @JsonbTypeInfo} keeps, on the type or any it extends. */
        private void requireTypeKeysFree(TypeMirror type, Map<String, Property> byJsonName) {
            AnnotationMirror typeInfo = Annotations.find(types.asElement(type), Annotations.JSONB_TYPE_INFO);
            Property named = typeInfo != null ? byJsonName.get(typeKey(typeInfo)) : null;
            if (typeInfo != null && named != null) {
                throw new Invalid(named.element, "The property is named \"" + typeKey(typeInfo)
                        + "\" in JSON, the key @JsonbTypeInfo keeps for the subtype");
            }
            for (TypeMirror supertype : types.directSupertypes(type)) {
                requireTypeKeysFree(supertype, byJsonName);
            }
        }

        private @Nullable Property byJsonOrJavaName(String name) {
            Property property = properties.get(name);
            if (property != null) {
                return property;
            }
            for (Property candidate : properties.values()) {
                if (candidate.jsonName.equals(name)) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
