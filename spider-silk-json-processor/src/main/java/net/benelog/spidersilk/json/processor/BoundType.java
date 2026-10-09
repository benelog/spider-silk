package net.benelog.spidersilk.json.processor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
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

    final TypeElement target;
    final @Nullable TypeElement mixin;
    final String packageName;
    final String generatedName;
    final List<Property> properties;
    final Creation creation;

    /** The creator's parameters, in order, for CONSTRUCTOR and FACTORY. */
    final List<Property> creatorParameters;
    final String factoryName;

    private BoundType(TypeElement target, @Nullable TypeElement mixin, String packageName, List<Property> properties,
            Creation creation, List<Property> creatorParameters, String factoryName) {
        this.target = target;
        this.mixin = mixin;
        this.packageName = packageName;
        this.generatedName = TypeNames.flatName(target) + "Json";
        this.properties = properties;
        this.creation = creation;
        this.creatorParameters = creatorParameters;
        this.factoryName = factoryName;
    }

    String generatedQualifiedName() {
        return packageName.isEmpty() ? generatedName : packageName + "." + generatedName;
    }

    String targetName() {
        return target.getQualifiedName().toString();
    }

    static BoundType of(TypeElement annotated, ProcessingEnvironment env, boolean explicitNames) {
        AnnotationMirror bound = Annotations.find(annotated, Annotations.JSON_BOUND);
        if (bound == null) {
            throw new Invalid(annotated, "Not annotated @JsonBound");
        }
        TypeElement target = annotated;
        TypeElement mixin = null;
        Object value = Annotations.value(bound, "value");
        if (value instanceof DeclaredType declared && !TypeNames.is(declared, "java.lang.Void")) {
            target = (TypeElement) declared.asElement();
            mixin = annotated;
        }
        if (target.getKind() != ElementKind.RECORD && target.getKind() != ElementKind.CLASS) {
            throw new Invalid(annotated, "@JsonBound binds a record or a class, not " + kindName(target.getKind()) + " "
                    + target.getQualifiedName());
        }
        if (target.getModifiers().contains(Modifier.ABSTRACT)) {
            throw new Invalid(annotated, "An abstract class cannot be created from a document: " + target.getQualifiedName());
        }
        if (!target.getTypeParameters().isEmpty()) {
            throw new Invalid(annotated, "A generic type cannot be bound: " + target.getQualifiedName());
        }
        String packageName = TypeNames.packageOf(annotated);
        if (!target.getModifiers().contains(Modifier.PUBLIC) && !packageName.equals(TypeNames.packageOf(target))) {
            throw new Invalid(annotated, target.getQualifiedName() + " is not public, so a codec in " + packageName + " cannot reach it");
        }
        for (String unsupported : List.of(Annotations.JSONB_VISIBILITY, Annotations.JSONB_TYPE_INFO)) {
            if (Annotations.has(target, unsupported) || (mixin != null && Annotations.has(mixin, unsupported))) {
                throw new Invalid(annotated, "@" + simpleName(unsupported) + " is not supported by the generated codec");
            }
        }

        Extraction extraction = new Extraction(env, target, mixin, explicitNames);
        extraction.run();
        return new BoundType(target, mixin, packageName, extraction.ordered(), extraction.creation,
                extraction.creatorParameters, extraction.factoryName);
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

        Extraction(ProcessingEnvironment env, TypeElement target, @Nullable TypeElement mixin, boolean explicitNames) {
            this.env = env;
            this.types = env.getTypeUtils();
            this.target = target;
            this.mixin = mixin;
            this.explicitNames = explicitNames;
        }

        void run() {
            typeNillable = nillable(target) || (mixin != null && nillable(mixin));
            typeDateFormat = Annotations.find(target, Annotations.JSONB_DATE_FORMAT);
            if (mixin != null && Annotations.find(mixin, Annotations.JSONB_DATE_FORMAT) != null) {
                typeDateFormat = Annotations.find(mixin, Annotations.JSONB_DATE_FORMAT);
            }
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

        private static boolean nillable(Element element) {
            AnnotationMirror mirror = Annotations.find(element, Annotations.JSONB_NILLABLE);
            return mirror != null && !Boolean.FALSE.equals(Annotations.value(mirror, "value"));
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
                property.getter = "value." + name + "()";
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

        /** One walk over the members in declaration order: a property's place is where its name first appears, a private field included. */
        private void beanClass() {
            for (Element member : target.getEnclosedElements()) {
                if (member.getModifiers().contains(Modifier.STATIC)) {
                    continue;
                }
                if (member instanceof VariableElement field && member.getKind() == ElementKind.FIELD) {
                    if (field.getModifiers().contains(Modifier.TRANSIENT)) {
                        continue;
                    }
                    String name = field.getSimpleName().toString();
                    declarations.computeIfAbsent(name, n -> new ArrayList<>()).add(field);
                    if (field.getModifiers().contains(Modifier.PUBLIC)) {
                        Property property = new Property(name, field.asType(), field);
                        property.getter = "value." + name;
                        property.field = !field.getModifiers().contains(Modifier.FINAL);
                        properties.put(name, property);
                    }
                } else if (member instanceof ExecutableElement method && member.getKind() == ElementKind.METHOD
                        && method.getModifiers().contains(Modifier.PUBLIC)) {
                    String methodName = method.getSimpleName().toString();
                    String name = propertyName(methodName, method);
                    if (name == null) {
                        continue;
                    }
                    declarations.computeIfAbsent(name, n -> new ArrayList<>()).add(method);
                    if (method.getParameters().isEmpty() && method.getReturnType().getKind() != TypeKind.VOID
                            && !methodName.startsWith("set")) {
                        Property property = properties.get(name);
                        if (property == null) {
                            property = new Property(name, method.getReturnType(), method);
                            properties.put(name, property);
                        }
                        property.getter = "value." + methodName + "()";
                    } else if (method.getParameters().size() == 1 && methodName.startsWith("set")) {
                        Property property = properties.get(name);
                        if (property == null) {
                            property = new Property(name, method.getParameters().get(0).asType(), method);
                            properties.put(name, property);
                        }
                        property.setter = methodName;
                    }
                }
            }
        }

        /** The property a getter or a setter is for, by JavaBeans convention, or null for a method that is neither. */
        private static @Nullable String propertyName(String methodName, ExecutableElement method) {
            if (methodName.startsWith("get") && methodName.length() > 3 && method.getParameters().isEmpty()) {
                return decapitalize(methodName.substring(3));
            }
            if (methodName.startsWith("is") && methodName.length() > 2 && method.getParameters().isEmpty()
                    && method.getReturnType().getKind() == TypeKind.BOOLEAN) {
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
                String name = parameter.getSimpleName().toString();
                AnnotationMirror named = Annotations.find(parameter, Annotations.JSONB_PROPERTY);
                String jsonName = named != null ? Annotations.string(named, "value") : null;
                Property property = properties.get(name);
                if (property == null) {
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
                        && !samePackage() && isCanonical(constructor)) {
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
                if (constructor.getParameters().isEmpty()
                        && (constructor.getModifiers().contains(Modifier.PUBLIC) || samePackage())) {
                    return;
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
                for (String unsupported : List.of(Annotations.JSONB_NUMBER_FORMAT, Annotations.JSONB_TYPE_SERIALIZER,
                        Annotations.JSONB_TYPE_DESERIALIZER)) {
                    if (Annotations.has(element, unsupported)) {
                        throw new Invalid(element, "@" + simpleName(unsupported) + " is not supported by the generated codec");
                    }
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
                    if (pattern.equals("##time-in-millis")) {
                        throw new Invalid(property.element, "@JsonbDateFormat.TIME_IN_MILLIS is not supported by the generated codec");
                    }
                    property.dateFormat = pattern;
                    property.dateLocale = locale != null && !locale.equals("##default") ? locale : null;
                }
            }
            AnnotationMirror adapter = first(declared, Annotations.JSONB_TYPE_ADAPTER);
            if (adapter != null) {
                adapter(property, adapter);
            }
        }

        private List<Element> mixinMembers(String name) {
            List<Element> members = new ArrayList<>();
            if (mixin == null) {
                return members;
            }
            for (Element member : mixin.getEnclosedElements()) {
                String memberName = member.getSimpleName().toString();
                String propertyName = member instanceof ExecutableElement method
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

        /** A {@code @JsonbTypeAdapter}: the adapter class, created with {@code new}, and the type it puts on the wire. */
        private void adapter(Property property, AnnotationMirror mirror) {
            Object value = Annotations.value(mirror, "value");
            if (!(value instanceof DeclaredType adapterType)) {
                throw new Invalid(property.element, "@JsonbTypeAdapter names no adapter class");
            }
            TypeElement adapter = (TypeElement) adapterType.asElement();
            boolean constructible = false;
            for (ExecutableElement constructor : ElementFilter.constructorsIn(adapter.getEnclosedElements())) {
                if (constructor.getParameters().isEmpty() && constructor.getModifiers().contains(Modifier.PUBLIC)) {
                    constructible = true;
                }
            }
            if (!constructible || !adapter.getModifiers().contains(Modifier.PUBLIC)) {
                throw new Invalid(property.element, "The adapter " + adapter.getQualifiedName()
                        + " needs a public no-argument constructor, since the generated codec creates it with new");
            }
            DeclaredType adapterInterface = findAdapterInterface(adapterType);
            if (adapterInterface == null || adapterInterface.getTypeArguments().size() != 2) {
                throw new Invalid(property.element, adapter.getQualifiedName() + " does not implement JsonbAdapter<Original, Adapted>");
            }
            TypeMirror original = adapterInterface.getTypeArguments().get(0);
            if (!types.isSameType(types.erasure(original), types.erasure(property.type))) {
                throw new Invalid(property.element, "The adapter " + adapter.getQualifiedName() + " adapts "
                        + TypeNames.of(original) + ", not " + TypeNames.of(property.type));
            }
            property.adapter = adapter;
            property.adapted = adapterInterface.getTypeArguments().get(1);
        }

        private @Nullable DeclaredType findAdapterInterface(TypeMirror type) {
            for (TypeMirror supertype : types.directSupertypes(type)) {
                if (TypeNames.is(supertype, Annotations.JSONB_ADAPTER)) {
                    return (DeclaredType) supertype;
                }
                DeclaredType found = findAdapterInterface(supertype);
                if (found != null) {
                    return found;
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
            env.getElementUtils(); // the environment is held for the messages above; nothing else is read from it here
            return ordered;
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
