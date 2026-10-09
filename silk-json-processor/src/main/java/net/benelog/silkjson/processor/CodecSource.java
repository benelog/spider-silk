package net.benelog.silkjson.processor;

import java.util.List;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

import org.jspecify.annotations.Nullable;

/**
 * The Java source of a codec: a final class beside the bound type, with a
 * {@code CODEC} constant, a {@code write} that puts each property straight
 * into the {@code JsonOutput}, and a {@code read} that steps through the
 * object once, matching keys as bytes, and calls the type's own constructor.
 */
final class CodecSource {

    private static final String JSON = "net.benelog.silkjson.";
    private static final String NULLABLE = "@org.jspecify.annotations.Nullable ";

    private static final Set<String> TIME_PARSED = Set.of(
            "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime", "java.time.OffsetDateTime",
            "java.time.OffsetTime", "java.time.ZonedDateTime", "java.time.Instant", "java.time.Year", "java.time.YearMonth",
            "java.time.MonthDay");
    private static final Set<String> TIME_PLAIN = Set.of("java.time.Duration", "java.time.Period");
    private static final Set<String> LISTS = Set.of("java.util.List", "java.util.Collection", "java.lang.Iterable");
    private static final Set<String> SETS = Set.of("java.util.Set");

    private final BoundType type;
    private final StringBuilder helpers = new StringBuilder();
    private int helperCount;
    private boolean readShort;
    private boolean readByte;
    private boolean readChar;

    private CodecSource(BoundType type) {
        this.type = type;
    }

    static String of(BoundType type) {
        return new CodecSource(type).generate();
    }

    private String generate() {
        StringBuilder body = new StringBuilder();
        writeMethod(body);
        readMethod(body);

        StringBuilder source = new StringBuilder();
        if (!type.packageName.isEmpty()) {
            source.append("package ").append(type.packageName).append(";\n\n");
        }
        source.append("@javax.annotation.processing.Generated(\"net.benelog.silkjson.processor.JsonProcessor\")\n");
        source.append("public final class ").append(type.generatedName)
                .append(" implements ").append(JSON).append("JsonCodec<").append(type.targetName()).append("> {\n\n");
        source.append("    public static final ").append(type.generatedName).append(" CODEC = new ")
                .append(type.generatedName).append("();\n\n");
        for (Property property : type.properties) {
            source.append("    private static final ").append(JSON).append("JsonKey K_").append(property.name)
                    .append(" = ").append(JSON).append("JsonKey.of(").append(literal(property.jsonName)).append(");\n");
        }
        for (Property property : type.properties) {
            if (property.dateFormat != null) {
                source.append("    private static final java.time.format.DateTimeFormatter F_").append(property.name)
                        .append(" = java.time.format.DateTimeFormatter.ofPattern(").append(literal(property.dateFormat));
                if (property.dateLocale != null) {
                    source.append(", java.util.Locale.forLanguageTag(").append(literal(property.dateLocale)).append(")");
                }
                source.append(");\n");
            }
            if (property.adapter != null) {
                String adapter = property.adapter.getQualifiedName().toString();
                source.append("    private static final ").append(adapter).append(" A_").append(property.name)
                        .append(" = new ").append(adapter).append("();\n");
            }
        }
        source.append("\n    private ").append(type.generatedName).append("() {\n    }\n\n");
        source.append(body);
        source.append(helpers);
        source.append(supportMethods());
        source.append("}\n");
        return source.toString();
    }

    // ---- write ----

    private void writeMethod(StringBuilder out) {
        out.append("    @Override\n");
        out.append("    public void write(").append(type.targetName()).append(" value, ").append(JSON).append("JsonOutput out) {\n");
        out.append("        out.object();\n");
        for (Property property : type.properties) {
            if (property.writable()) {
                writeProperty(out, property);
            }
        }
        out.append("        out.end();\n");
        out.append("    }\n\n");
    }

    private void writeProperty(StringBuilder out, Property property) {
        String getter = property.getter;
        if (getter == null) {
            return;
        }
        String key = "K_" + property.name;
        TypeMirror type = property.type;
        if (type.getKind().isPrimitive()) {
            out.append("        out.name(").append(key).append(");\n");
            out.append("        ").append(writeValue(type, getter, property, 2)).append("\n");
            return;
        }
        String local = "p_" + property.name;
        out.append("        ").append(nullableName(type)).append(local).append(" = ").append(getter).append(";\n");
        if (property.adapter != null && property.adapted != null) {
            String adapted = "a_" + property.name;
            out.append("        if (").append(local).append(" != null) {\n");
            out.append("            ").append(nullableName(property.adapted)).append(adapted).append(";\n");
            out.append("            try {\n");
            out.append("                ").append(adapted).append(" = A_").append(property.name).append(".adaptToJson(")
                    .append(local).append(");\n");
            out.append("            } catch (java.lang.Exception e) {\n");
            out.append("                throw new java.lang.IllegalArgumentException(e.getMessage(), e);\n");
            out.append("            }\n");
            out.append("            if (").append(adapted).append(" != null) {\n");
            out.append("                out.name(").append(key).append(");\n");
            out.append("                ").append(writeValue(property.adapted, adapted, null, 4)).append("\n");
            if (property.nillable) {
                out.append("            } else {\n                out.putNull(").append(key).append(");\n");
            }
            out.append("            }\n");
            if (property.nillable) {
                out.append("        } else {\n            out.putNull(").append(key).append(");\n");
            }
            out.append("        }\n");
            return;
        }
        String present = local + " != null";
        String expr = local;
        TypeMirror written = type;
        if (TypeNames.is(type, "java.util.Optional")) {
            present = local + " != null && " + local + ".isPresent()";
            expr = local + ".get()";
            written = TypeNames.argument(type, 0);
        } else if (TypeNames.is(type, "java.util.OptionalInt") || TypeNames.is(type, "java.util.OptionalLong")
                || TypeNames.is(type, "java.util.OptionalDouble")) {
            present = local + " != null && " + local + ".isPresent()";
            String accessor = TypeNames.is(type, "java.util.OptionalInt") ? "getAsInt"
                    : TypeNames.is(type, "java.util.OptionalLong") ? "getAsLong" : "getAsDouble";
            out.append("        if (").append(present).append(") {\n");
            out.append("            out.name(").append(key).append(");\n");
            out.append("            out.value(").append(local).append(".").append(accessor).append("());\n");
            if (property.nillable) {
                out.append("        } else {\n            out.putNull(").append(key).append(");\n");
            }
            out.append("        }\n");
            return;
        }
        out.append("        if (").append(present).append(") {\n");
        out.append("            out.name(").append(key).append(");\n");
        out.append("            ").append(writeValue(written, expr, property, 3)).append("\n");
        if (property.nillable) {
            out.append("        } else {\n            out.putNull(").append(key).append(");\n");
        }
        out.append("        }\n");
    }

    /** A statement writing a value, known not to be null, of the type as the next value. */
    private String writeValue(TypeMirror type, String expr, @Nullable Property property, int indent) {
        return switch (type.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> "out.value(" + expr + ");";
            case CHAR -> "out.value(java.lang.String.valueOf(" + expr + "));";
            case DECLARED -> writeDeclared((DeclaredType) type, expr, property, indent);
            default -> throw unsupported(type, property);
        };
    }

    private String writeDeclared(DeclaredType type, String expr, @Nullable Property property, int indent) {
        String name = TypeNames.qualifiedName(type);
        switch (name) {
            case "java.lang.String", "java.lang.Boolean", "java.lang.Integer", "java.lang.Long", "java.lang.Short",
                    "java.lang.Byte", "java.lang.Double", "java.lang.Float", "java.math.BigDecimal", "java.math.BigInteger",
                    "net.benelog.silkjson.JsonValue", "net.benelog.silkjson.JsonObject",
                    "net.benelog.silkjson.JsonArray" -> {
                return "out.value(" + expr + ");";
            }
            case "java.lang.Character", "java.util.UUID", "java.net.URI", "java.time.ZoneId", "java.time.ZoneOffset" -> {
                return "out.value(" + expr + ".toString());";
            }
            default -> {
                // falls through to the cases below
            }
        }
        if (TIME_PARSED.contains(name) || TIME_PLAIN.contains(name)) {
            if (property != null && property.dateFormat != null && TIME_PARSED.contains(name)) {
                return "out.value(F_" + property.name + ".format(" + expr + "));";
            }
            return "out.value(" + expr + ".toString());";
        }
        Element element = type.asElement();
        if (element.getKind() == ElementKind.ENUM) {
            return "out.value(" + expr + ".name());";
        }
        if (name.equals("java.util.Optional")) {
            TypeMirror inner = TypeNames.argument(type, 0);
            return "if (" + expr + ".isPresent()) {\n" + pad(indent + 1) + writeValue(inner, expr + ".get()", null, indent + 1)
                    + "\n" + pad(indent) + "} else {\n" + pad(indent + 1) + "out.nullValue();\n" + pad(indent) + "}";
        }
        if (LISTS.contains(name) || SETS.contains(name)) {
            return writeCollection(type, expr);
        }
        if (name.equals("java.util.Map")) {
            return writeMap(type, expr, property);
        }
        if (Annotations.has(element, Annotations.JSON_BOUND)) {
            return boundCodec((TypeElement) element) + ".write(" + expr + ", out);";
        }
        throw unsupported(type, property);
    }

    private String writeCollection(DeclaredType type, String expr) {
        TypeMirror elementType = TypeNames.argument(type, 0);
        String helper = "write_" + ++helperCount;
        helpers.append("    private static void ").append(helper).append("(").append(TypeNames.of(type))
                .append(" values, ").append(JSON).append("JsonOutput out) {\n");
        helpers.append("        out.array();\n");
        helpers.append("        for (").append(nullableName(elementType)).append("element : values) {\n");
        helpers.append("            if (element == null) {\n                out.nullValue();\n            } else {\n");
        helpers.append("                ").append(writeValue(elementType, "element", null, 4)).append("\n");
        helpers.append("            }\n        }\n        out.end();\n    }\n\n");
        return helper + "(" + expr + ", out);";
    }

    private String writeMap(DeclaredType type, String expr, @Nullable Property property) {
        TypeMirror keyType = TypeNames.argument(type, 0);
        if (!TypeNames.is(keyType, "java.lang.String")) {
            throw new Invalid(property != null ? property.element : type.asElement(),
                    "A map bound to a JSON object has String keys, not " + TypeNames.of(keyType));
        }
        TypeMirror valueType = TypeNames.argument(type, 1);
        String helper = "write_" + ++helperCount;
        helpers.append("    private static void ").append(helper).append("(").append(TypeNames.of(type))
                .append(" values, ").append(JSON).append("JsonOutput out) {\n");
        helpers.append("        out.object();\n");
        helpers.append("        for (java.util.Map.Entry<java.lang.String, ").append(TypeNames.of(valueType))
                .append("> entry : values.entrySet()) {\n");
        helpers.append("            out.name(entry.getKey());\n");
        helpers.append("            ").append(nullableName(valueType)).append("element = entry.getValue();\n");
        helpers.append("            if (element == null) {\n                out.nullValue();\n            } else {\n");
        helpers.append("                ").append(writeValue(valueType, "element", null, 4)).append("\n");
        helpers.append("            }\n        }\n        out.end();\n    }\n\n");
        return helper + "(" + expr + ", out);";
    }

    // ---- read ----

    private void readMethod(StringBuilder out) {
        out.append("    @Override\n");
        out.append("    public ").append(type.targetName()).append(" read(").append(JSON).append("JsonInput in) {\n");
        out.append("        in.object();\n");
        List<Property> readable = type.properties.stream().filter(Property::readable).toList();
        for (Property property : readable) {
            out.append("        ").append(local(property)).append(";\n");
            if (seenFlag(property)) {
                out.append("        boolean s_").append(property.name).append(" = false;\n");
            }
        }
        if (readable.isEmpty()) {
            out.append("        while (in.nextKey()) {\n            in.skipValue();\n        }\n");
        } else {
            // A case reads one property and tries the key declared after it in
            // place, which a document written from this declaration has there;
            // any other key goes through keyIndex.
            out.append("        int key = in.nextKeyIs(K_").append(readable.get(0).name).append(") ? 0 : keyIndex(in);\n");
            out.append("        while (key != -1) {\n");
            out.append("            switch (key) {\n");
            for (int i = 0; i < readable.size(); i++) {
                out.append("                case ").append(i).append(" -> {\n");
                StringBuilder body = new StringBuilder();
                readProperty(body, readable.get(i));
                out.append(body.toString().indent(4));
                if (i + 1 < readable.size()) {
                    out.append("                    key = in.nextKeyIs(K_").append(readable.get(i + 1).name).append(") ? ")
                            .append(i + 1).append(" : keyIndex(in);\n");
                } else {
                    out.append("                    key = keyIndex(in);\n");
                }
                out.append("                }\n");
            }
            out.append("                default -> {\n");
            out.append("                    in.skipValue();\n");
            out.append("                    key = keyIndex(in);\n");
            out.append("                }\n");
            out.append("            }\n");
            out.append("        }\n");
            keyIndexMethod(readable);
        }
        for (Property property : readable) {
            if (property.required()) {
                String check = property.type.getKind().isPrimitive() ? "!s_" + property.name : "p_" + property.name + " == null";
                out.append("        if (").append(check).append(") {\n");
                out.append("            throw new ").append(JSON).append("JsonException(")
                        .append(literal("Missing key in JSON object: " + property.jsonName)).append(");\n");
                out.append("        }\n");
            }
        }
        StringBuilder arguments = new StringBuilder();
        for (Property parameter : type.creatorParameters) {
            String argument = parameter.excluded
                    ? (TypeNames.is(parameter.type, "java.util.Optional") ? "java.util.Optional.empty()" : "null")
                    : "p_" + parameter.name;
            arguments.append(arguments.length() > 0 ? ", " : "").append(argument);
        }
        String created = switch (type.creation) {
            case CONSTRUCTOR -> "new " + type.targetName() + "(" + arguments + ")";
            case FACTORY -> type.targetName() + "." + type.factoryName + "(" + arguments + ")";
            case SETTERS -> "new " + type.targetName() + "()";
        };
        List<Property> set = readable.stream().filter(p -> p.creatorIndex < 0).toList();
        if (set.isEmpty()) {
            out.append("        return ").append(created).append(";\n");
        } else {
            out.append("        ").append(type.targetName()).append(" result = ").append(created).append(";\n");
            for (Property property : set) {
                out.append("        if (s_").append(property.name).append(") {\n");
                if (property.setter != null) {
                    out.append("            result.").append(property.setter).append("(p_").append(property.name).append(");\n");
                } else {
                    out.append("            result.").append(property.name).append(" = p_").append(property.name).append(";\n");
                }
                out.append("        }\n");
            }
            out.append("        return result;\n");
        }
        out.append("    }\n\n");
    }

    /** The helper the read goes to for a key out of the declared order: the key's index among the readable properties, -2 for a key it does not know, or -1 at the end of the object. */
    private void keyIndexMethod(List<Property> readable) {
        helpers.append("    private static int keyIndex(").append(JSON).append("JsonInput in) {\n");
        helpers.append("        if (!in.nextKey()) {\n            return -1;\n        }\n");
        for (int i = 0; i < readable.size(); i++) {
            helpers.append("        if (in.keyIs(K_").append(readable.get(i).name).append(")) {\n");
            helpers.append("            return ").append(i).append(";\n        }\n");
        }
        helpers.append("        return -2;\n    }\n\n");
    }

    /** Whether the read keeps a flag for having seen the property: a required primitive, or one set after creation. */
    private static boolean seenFlag(Property property) {
        return property.creatorIndex < 0 || (property.required() && property.type.getKind().isPrimitive());
    }

    private String local(Property property) {
        TypeMirror type = property.type;
        String name = "p_" + property.name;
        if (type.getKind().isPrimitive()) {
            String zero = switch (type.getKind()) {
                case BOOLEAN -> "false";
                case LONG -> "0L";
                case FLOAT -> "0.0f";
                case DOUBLE -> "0.0";
                case CHAR -> "'\\0'";
                case BYTE -> "(byte) 0";
                case SHORT -> "(short) 0";
                default -> "0";
            };
            return TypeNames.of(type) + " " + name + " = " + zero;
        }
        if (TypeNames.is(type, "java.util.Optional")) {
            return TypeNames.of(type) + " " + name + " = java.util.Optional.empty()";
        }
        for (String optional : List.of("java.util.OptionalInt", "java.util.OptionalLong", "java.util.OptionalDouble")) {
            if (TypeNames.is(type, optional)) {
                return optional + " " + name + " = " + optional + ".empty()";
            }
        }
        return nullableName(type) + name + " = null";
    }

    private void readProperty(StringBuilder out, Property property) {
        TypeMirror type = property.type;
        String local = "p_" + property.name;
        if (property.adapter != null && property.adapted != null) {
            String read = readValue(property.adapted, null);
            if (property.nullable) {
                out.append("                if (in.readNull()) {\n                    ").append(local).append(" = null;\n");
                out.append("                } else {\n");
                out.append(adaptFrom(property, read, 5));
                out.append("                }\n");
            } else {
                out.append(adaptFrom(property, read, 4));
            }
        } else if (type.getKind().isPrimitive()) {
            out.append("                ").append(local).append(" = ").append(readValue(type, property)).append(";\n");
        } else if (TypeNames.is(type, "java.util.Optional")) {
            TypeMirror inner = TypeNames.argument(type, 0);
            out.append("                ").append(local).append(" = in.readNull() ? java.util.Optional.empty() : java.util.Optional.of(")
                    .append(readValue(inner, property)).append(");\n");
        } else if (TypeNames.is(type, "java.util.OptionalInt")) {
            out.append("                ").append(local).append(" = in.readNull() ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(in.readInt());\n");
        } else if (TypeNames.is(type, "java.util.OptionalLong")) {
            out.append("                ").append(local).append(" = in.readNull() ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(in.readLong());\n");
        } else if (TypeNames.is(type, "java.util.OptionalDouble")) {
            out.append("                ").append(local).append(" = in.readNull() ? java.util.OptionalDouble.empty() : java.util.OptionalDouble.of(in.readDouble());\n");
        } else if (property.nullable) {
            out.append("                ").append(local).append(" = in.readNull() ? null : ").append(readValue(type, property)).append(";\n");
        } else {
            out.append("                ").append(local).append(" = ").append(readValue(type, property)).append(";\n");
        }
        if (seenFlag(property)) {
            out.append("                s_").append(property.name).append(" = true;\n");
        }
    }

    private String adaptFrom(Property property, String read, int indent) {
        String p = pad(indent);
        return p + "try {\n"
                + p + "    p_" + property.name + " = A_" + property.name + ".adaptFromJson(" + read + ");\n"
                + p + "} catch (java.lang.Exception e) {\n"
                + p + "    throw new java.lang.IllegalArgumentException(e.getMessage(), e);\n"
                + p + "}\n";
    }

    /** An expression reading a value of the type, which rejects a JSON null itself. */
    private String readValue(TypeMirror type, @Nullable Property property) {
        return switch (type.getKind()) {
            case BOOLEAN -> "in.readBoolean()";
            case INT -> "in.readInt()";
            case LONG -> "in.readLong()";
            case DOUBLE -> "in.readDouble()";
            case SHORT -> {
                readShort = true;
                yield "readShort(in)";
            }
            case BYTE -> {
                readByte = true;
                yield "readByte(in)";
            }
            case FLOAT -> "in.readFloat()";
            case CHAR -> {
                readChar = true;
                yield "readChar(in)";
            }
            case DECLARED -> readDeclared((DeclaredType) type, property);
            default -> throw unsupported(type, property);
        };
    }

    private String readDeclared(DeclaredType type, @Nullable Property property) {
        String name = TypeNames.qualifiedName(type);
        switch (name) {
            case "java.lang.String" -> {
                return "in.readString()";
            }
            case "java.lang.Boolean" -> {
                return "in.readBoolean()";
            }
            case "java.lang.Integer" -> {
                return "in.readInt()";
            }
            case "java.lang.Long" -> {
                return "in.readLong()";
            }
            case "java.lang.Double" -> {
                return "in.readDouble()";
            }
            case "java.lang.Short" -> {
                readShort = true;
                return "readShort(in)";
            }
            case "java.lang.Byte" -> {
                readByte = true;
                return "readByte(in)";
            }
            case "java.lang.Float" -> {
                return "in.readFloat()";
            }
            case "java.lang.Character" -> {
                readChar = true;
                return "readChar(in)";
            }
            case "java.math.BigDecimal" -> {
                return "new java.math.BigDecimal(in.readNumber())";
            }
            case "java.math.BigInteger" -> {
                return "new java.math.BigInteger(in.readNumber())";
            }
            case "java.util.UUID" -> {
                return "java.util.UUID.fromString(in.readString())";
            }
            case "java.net.URI" -> {
                return "java.net.URI.create(in.readString())";
            }
            case "java.time.ZoneId" -> {
                return "java.time.ZoneId.of(in.readString())";
            }
            case "java.time.ZoneOffset" -> {
                return "java.time.ZoneOffset.of(in.readString())";
            }
            case "net.benelog.silkjson.JsonValue" -> {
                return "in.readValue()";
            }
            case "net.benelog.silkjson.JsonObject" -> {
                return "in.readValue().asObject()";
            }
            case "net.benelog.silkjson.JsonArray" -> {
                return "in.readValue().asArray()";
            }
            default -> {
                // falls through to the cases below
            }
        }
        if (TIME_PARSED.contains(name)) {
            if (property != null && property.dateFormat != null) {
                return name + ".parse(in.readString(), F_" + property.name + ")";
            }
            return name + ".parse(in.readString())";
        }
        if (TIME_PLAIN.contains(name)) {
            return name + ".parse(in.readString())";
        }
        Element element = type.asElement();
        if (element.getKind() == ElementKind.ENUM) {
            return name + ".valueOf(in.readString())";
        }
        if (name.equals("java.util.Optional")) {
            TypeMirror inner = TypeNames.argument(type, 0);
            return "(in.readNull() ? java.util.Optional.<" + TypeNames.of(inner) + ">empty() : java.util.Optional.of("
                    + readValue(inner, null) + "))";
        }
        if (LISTS.contains(name) || SETS.contains(name)) {
            return readCollection(type, SETS.contains(name));
        }
        if (name.equals("java.util.Map")) {
            return readMap(type, property);
        }
        if (Annotations.has(element, Annotations.JSON_BOUND)) {
            return boundCodec((TypeElement) element) + ".read(in)";
        }
        throw unsupported(type, property);
    }

    private String readCollection(DeclaredType type, boolean set) {
        TypeMirror elementType = TypeNames.argument(type, 0);
        String helper = "read_" + ++helperCount;
        String declared = TypeNames.of(type);
        String implementation = set ? "java.util.LinkedHashSet<>" : "java.util.ArrayList<>";
        String wrap = set ? "java.util.Collections.unmodifiableSet" : "java.util.Collections.unmodifiableList";
        String element = Annotations.isNullable(List.of(), elementType)
                ? "in.readNull() ? null : " + readValue(elementType, null)
                : readValue(elementType, null);
        helpers.append("    private static ").append(declared).append(" ").append(helper).append("(")
                .append(JSON).append("JsonInput in) {\n");
        helpers.append("        in.array();\n");
        helpers.append("        ").append(set ? "java.util.Set<" : "java.util.List<").append(TypeNames.of(elementType))
                .append("> values = new ").append(implementation).append("();\n");
        helpers.append("        while (in.nextElement()) {\n");
        helpers.append("            values.add(").append(element).append(");\n");
        helpers.append("        }\n");
        helpers.append("        return ").append(wrap).append("(values);\n");
        helpers.append("    }\n\n");
        return helper + "(in)";
    }

    private String readMap(DeclaredType type, @Nullable Property property) {
        TypeMirror keyType = TypeNames.argument(type, 0);
        if (!TypeNames.is(keyType, "java.lang.String")) {
            throw new Invalid(property != null ? property.element : type.asElement(),
                    "A map bound to a JSON object has String keys, not " + TypeNames.of(keyType));
        }
        TypeMirror valueType = TypeNames.argument(type, 1);
        String helper = "read_" + ++helperCount;
        String element = Annotations.isNullable(List.of(), valueType)
                ? "in.readNull() ? null : " + readValue(valueType, null)
                : readValue(valueType, null);
        helpers.append("    private static ").append(TypeNames.of(type)).append(" ").append(helper).append("(")
                .append(JSON).append("JsonInput in) {\n");
        helpers.append("        in.object();\n");
        helpers.append("        java.util.Map<java.lang.String, ").append(TypeNames.of(valueType))
                .append("> values = new java.util.LinkedHashMap<>();\n");
        helpers.append("        while (in.nextKey()) {\n");
        helpers.append("            values.put(in.key(), ").append(element).append(");\n");
        helpers.append("        }\n");
        helpers.append("        return java.util.Collections.unmodifiableMap(values);\n");
        helpers.append("    }\n\n");
        return helper + "(in)";
    }

    // ---- the pieces ----

    private String boundCodec(TypeElement bound) {
        String packageName = TypeNames.packageOf(bound);
        String name = TypeNames.flatName(bound) + "Json";
        return packageName.isEmpty() ? name + ".CODEC" : packageName + "." + name + ".CODEC";
    }

    /** The type's name with a type-use {@code @Nullable} before its simple name, and a space after, for a local that may hold null. */
    private static String nullableName(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return TypeNames.of(type) + " ";
        }
        String name = TypeNames.of(type);
        int generics = name.indexOf('<');
        String raw = generics < 0 ? name : name.substring(0, generics);
        int dot = raw.lastIndexOf('.');
        return raw.substring(0, dot + 1) + NULLABLE + raw.substring(dot + 1) + (generics < 0 ? "" : name.substring(generics)) + " ";
    }

    private Invalid unsupported(TypeMirror type, @Nullable Property property) {
        Element at = property != null ? property.element : this.type.target;
        return new Invalid(at, "The type " + type + " cannot be bound: annotate it @JsonBound, or adapt it with @JsonbTypeAdapter");
    }

    private static String pad(int level) {
        return "    ".repeat(level);
    }

    static String literal(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** The small readers the generated class carries for the types {@code JsonInput} has no method of its own for. */
    String supportMethods() {
        StringBuilder sb = new StringBuilder();
        if (readShort) {
            sb.append("    private static short readShort(").append(JSON).append("JsonInput in) {\n")
                    .append("        long value = in.readLong();\n")
                    .append("        if (value < java.lang.Short.MIN_VALUE || value > java.lang.Short.MAX_VALUE) {\n")
                    .append("            throw in.error(\"Not a 16-bit integer: \" + value);\n        }\n")
                    .append("        return (short) value;\n    }\n\n");
        }
        if (readByte) {
            sb.append("    private static byte readByte(").append(JSON).append("JsonInput in) {\n")
                    .append("        long value = in.readLong();\n")
                    .append("        if (value < java.lang.Byte.MIN_VALUE || value > java.lang.Byte.MAX_VALUE) {\n")
                    .append("            throw in.error(\"Not an 8-bit integer: \" + value);\n        }\n")
                    .append("        return (byte) value;\n    }\n\n");
        }
        if (readChar) {
            sb.append("    private static char readChar(").append(JSON).append("JsonInput in) {\n")
                    .append("        java.lang.String value = in.readString();\n")
                    .append("        if (value.length() != 1) {\n")
                    .append("            throw in.error(\"Not a single character\");\n        }\n")
                    .append("        return value.charAt(0);\n    }\n\n");
        }
        return sb.toString();
    }
}
