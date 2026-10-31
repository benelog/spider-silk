package net.benelog.silkjson.processor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;

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
    private static final String JSONB = Annotations.JSONB_CODECS + ".";

    private static final Set<String> TIME_PARSED = Set.of(
            "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime", "java.time.OffsetDateTime",
            "java.time.OffsetTime", "java.time.ZonedDateTime", "java.time.Instant", "java.time.Year", "java.time.YearMonth",
            "java.time.MonthDay");
    private static final Set<String> TIME_PLAIN = Set.of("java.time.Duration", "java.time.Period");
    private static final Set<String> LISTS = Set.of("java.util.List", "java.util.Collection", "java.lang.Iterable");
    private static final Set<String> SETS = Set.of("java.util.Set");

    /** The {@code java.time} types a number of milliseconds since the epoch can stand for, in UTC. */
    private static final Set<String> TIME_MILLIS = Set.of("java.time.Instant", "java.time.ZonedDateTime", "java.time.OffsetDateTime",
            "java.time.LocalDateTime", "java.time.LocalDate");

    /**
     * The most readable properties the read tries in a chain, and the most it
     * switches on the head of a key for. A wider type would bring its read
     * near the size the JIT compiler gives up at, 8000 bytes of bytecode.
     */
    private static final int CHAIN_MAX = 32;
    private static final int SWITCH_MAX = 64;

    private final BoundType type;
    private final StringBuilder helpers = new StringBuilder();
    private int helperCount;

    /** The enums the codec writes or reads, by qualified name, each with the name of its tables. */
    private final Map<String, String> enums = new LinkedHashMap<>();
    private boolean readShort;
    private boolean readByte;
    private boolean readChar;

    /** The properties a number format was used for, each with a format of its own, {@code N_}. */
    private final Set<Property> formatted = new LinkedHashSet<>();

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
        source.append("public final class ").append(type.generatedName).append(type.typeParameters())
                .append(" implements ").append(JSON).append("JsonCodec<").append(type.targetType()).append("> {\n\n");
        if (!type.generic()) {
            source.append("    public static final ").append(type.generatedName).append(" CODEC = new ")
                    .append(type.generatedName).append("();\n\n");
        }
        for (Property property : type.properties) {
            source.append("    private static final ").append(JSON).append("JsonKey K_").append(property.name)
                    .append(" = ").append(JSON).append("JsonKey.of(").append(literal(property.jsonName)).append(");\n");
        }
        for (Map.Entry<String, String> constants : enums.entrySet()) {
            source.append("    private static final ").append(constants.getKey()).append("[] C_").append(constants.getValue())
                    .append(" = ").append(constants.getKey()).append(".values();\n");
            source.append("    private static final ").append(JSON).append("JsonKey[] V_").append(constants.getValue())
                    .append(" = ").append(JSON).append("JsonKey.names(C_").append(constants.getValue()).append(");\n");
        }
        if (!type.subtypes.isEmpty()) {
            source.append("    private static final ").append(JSON).append("JsonKey T_key = ").append(JSON).append("JsonKey.of(")
                    .append(literal(type.typeKey)).append(");\n");
            source.append("    private static final ").append(JSON).append("JsonKey[] T_aliases = {");
            for (int i = 0; i < type.subtypes.size(); i++) {
                source.append(i > 0 ? ", " : "").append(JSON).append("JsonKey.of(").append(literal(type.subtypes.get(i).alias())).append(")");
            }
            source.append("};\n");
        }
        for (Property property : formatted) {
            source.append("    private static final java.text.DecimalFormat N_").append(property.name).append(" = numberFormat(")
                    .append(literal(String.valueOf(property.numberFormat))).append(", ")
                    .append(property.numberLocale != null
                            ? "java.util.Locale.forLanguageTag(" + literal(property.numberLocale) + ")" : "java.util.Locale.ROOT")
                    .append(");\n");
        }
        List<Property> converted = new ArrayList<>(type.properties);
        Property whole = type.whole;
        if (whole != null) {
            converted.add(whole);
        }
        for (Property property : converted) {
            if (property.dateFormat != null) {
                source.append("    private static final java.time.format.DateTimeFormatter F_").append(property.name)
                        .append(" = java.time.format.DateTimeFormatter.ofPattern(").append(literal(property.dateFormat));
                if (property.dateLocale != null) {
                    source.append(", java.util.Locale.forLanguageTag(").append(literal(property.dateLocale)).append(")");
                }
                source.append(")");
                if (holds(property.type, "java.time.Instant")) {
                    // an Instant has no zone of its own to be formatted in
                    source.append(".withZone(java.time.ZoneOffset.UTC)");
                }
                source.append(";\n");
            }
            if (property.adapter != null) {
                String adapter = property.adapter.getQualifiedName().toString();
                source.append("    private static final ").append(adapter).append(" A_").append(property.name)
                        .append(" = new ").append(adapter).append("();\n");
            }
            if (property.serializer != null) {
                String serializer = property.serializer.getQualifiedName().toString();
                source.append("    private static final ").append(serializer).append(" S_").append(property.name)
                        .append(" = new ").append(serializer).append("();\n");
            }
            if (property.deserializer != null) {
                String deserializer = property.deserializer.getQualifiedName().toString();
                source.append("    private static final ").append(deserializer).append(" D_").append(property.name)
                        .append(" = new ").append(deserializer).append("();\n");
            }
        }
        if (type.generic()) {
            constructor(source);
        } else {
            source.append("\n    private ").append(type.generatedName).append("() {\n    }\n\n");
        }
        source.append(body);
        source.append(helpers);
        source.append(supportMethods());
        source.append("}\n");
        return source.toString();
    }

    /**
     * What a generic type's codec has in place of its constant: a codec for
     * each type variable, handed in through {@code of}, with the name of the
     * variable behind a dollar sign.
     */
    private void constructor(StringBuilder source) {
        StringBuilder parameters = new StringBuilder();
        StringBuilder arguments = new StringBuilder();
        source.append("\n");
        for (String variable : type.typeVariables()) {
            String codec = JSON + "JsonCodec<" + variable + "> $" + variable;
            source.append("    private final ").append(codec).append(";\n");
            parameters.append(parameters.length() > 0 ? ", " : "").append(codec);
            arguments.append(arguments.length() > 0 ? ", " : "").append("$").append(variable);
        }
        source.append("\n    private ").append(type.generatedName).append("(").append(parameters).append(") {\n");
        for (String variable : type.typeVariables()) {
            source.append("        this.$").append(variable).append(" = $").append(variable).append(";\n");
        }
        source.append("    }\n\n");
        source.append("    /** The codec of this type, given a codec for each of its type arguments. */\n");
        source.append("    public static ").append(type.typeParameters()).append(" ").append(type.generatedName).append(type.typeArguments())
                .append(" of(").append(parameters).append(") {\n");
        source.append("        return new ").append(type.generatedName).append("<>(").append(arguments).append(");\n");
        source.append("    }\n\n");
    }

    /** The start of a helper of the generated class: an instance method in a generic codec, whose helpers use its codecs. */
    private String helper() {
        return type.generic() ? "    private " : "    private static ";
    }

    // ---- write ----

    private void writeMethod(StringBuilder out) {
        out.append("    @Override\n");
        out.append("    public void write(").append(type.targetType()).append(" $value, ").append(JSON).append("JsonOutput $out) {\n");
        Property whole = type.whole;
        if (whole != null && whole.adapter != null && whole.adapted != null) {
            writeAdaptedWhole(out, whole, whole.adapted);
            return;
        }
        if (whole != null && whole.serializer != null) {
            out.append("        ").append(JSONB).append("write(S_").append(whole.name).append(", $value, $out);\n");
            out.append("    }\n\n");
            return;
        }
        out.append("        $out.object();\n");
        out.append("        writeMembers($value, $out);\n");
        out.append("        $out.end();\n");
        out.append("    }\n\n");
        out.append("    /** Writes the value's members into the object the output has open: a parent type's codec opens it, with its key in it first. */\n");
        out.append("    public void writeMembers(").append(type.targetType()).append(" $value, ").append(JSON).append("JsonOutput $out) {\n");
        for (int i = 0; i < type.subtypes.size(); i++) {
            BoundType.Subtype subtype = type.subtypes.get(i);
            out.append("        if ($value instanceof ").append(subtype.type().getQualifiedName()).append(" $subtype) {\n");
            out.append("            $out.put(T_key, T_aliases[").append(i).append("]);\n");
            out.append("            ").append(boundCodec((DeclaredType) subtype.type().asType())).append(".writeMembers($subtype, $out);\n");
            out.append("            return;\n");
            out.append("        }\n");
        }
        if (!type.subtypes.isEmpty() && type.abstractType()) {
            out.append("        throw new java.lang.IllegalArgumentException(")
                    .append(literal("Not one of the subtypes @JsonbTypeInfo lists on " + type.targetName())).append(");\n");
        }
        for (Property property : type.properties) {
            if (property.writable()) {
                writeProperty(out, property);
            }
        }
        out.append("    }\n\n");
    }

    /** The write of a type a {@code @JsonbTypeAdapter} on the type converts: the adapted value is what goes out. */
    private void writeAdaptedWhole(StringBuilder out, Property whole, TypeMirror adapted) {
        out.append("        ").append(nullableName(adapted)).append("$adapted;\n");
        out.append("        try {\n");
        out.append("            $adapted = A_").append(whole.name).append(".adaptToJson($value);\n");
        out.append("        } catch (java.lang.Exception $e) {\n");
        out.append("            throw new java.lang.IllegalArgumentException($e.getMessage(), $e);\n");
        out.append("        }\n");
        out.append("        if ($adapted == null) {\n            $out.nullValue();\n        } else {\n");
        out.append("            ").append(writeValue(adapted, "$adapted", null, 3)).append("\n");
        out.append("        }\n");
        out.append("    }\n\n");
    }

    private void writeProperty(StringBuilder out, Property property) {
        String getter = property.getter;
        if (getter == null) {
            return;
        }
        String key = "K_" + property.name;
        TypeMirror type = property.type;
        if (property.serializer != null) {
            writeSerialized(out, property, getter, key);
            return;
        }
        if (type.getKind().isPrimitive()) {
            out.append("        ").append(writeMember(key, type, getter, property, 2)).append("\n");
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
            out.append("            } catch (java.lang.Exception $e) {\n");
            out.append("                throw new java.lang.IllegalArgumentException($e.getMessage(), $e);\n");
            out.append("            }\n");
            out.append("            if (").append(adapted).append(" != null) {\n");
            out.append("                $out.name(").append(key).append(");\n");
            out.append("                ").append(writeValue(property.adapted, adapted, null, 4)).append("\n");
            if (property.nillable) {
                out.append("            } else {\n                $out.putNull(").append(key).append(");\n");
            }
            out.append("            }\n");
            if (property.nillable) {
                out.append("        } else {\n            $out.putNull(").append(key).append(");\n");
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
        }
        String accessor = type.getKind() == TypeKind.DECLARED ? optionalAccessor(TypeNames.qualifiedName((DeclaredType) type)) : null;
        if (accessor != null) {
            present = local + " != null && " + local + ".isPresent()";
            out.append("        if (").append(present).append(") {\n");
            out.append("            $out.name(").append(key).append(");\n");
            out.append("            ").append(writeOptionalValue(local + "." + accessor + "()", accessor, property)).append("\n");
            if (property.nillable) {
                out.append("        } else {\n            $out.putNull(").append(key).append(");\n");
            }
            out.append("        }\n");
            return;
        }
        out.append("        if (").append(present).append(") {\n");
        out.append("            ").append(writeMember(key, written, expr, property, 3)).append("\n");
        if (property.nillable) {
            out.append("        } else {\n            $out.putNull(").append(key).append(");\n");
        }
        out.append("        }\n");
    }

    /** A property a {@code @JsonbTypeSerializer} writes: the serializer has the value once the key is out. */
    private void writeSerialized(StringBuilder out, Property property, String getter, String key) {
        if (property.type.getKind().isPrimitive()) {
            out.append("        $out.name(").append(key).append(");\n");
            out.append("        ").append(JSONB).append("write(S_").append(property.name).append(", ").append(getter).append(", $out);\n");
            return;
        }
        String local = "p_" + property.name;
        out.append("        ").append(nullableName(property.type)).append(local).append(" = ").append(getter).append(";\n");
        out.append("        if (").append(local).append(" != null) {\n");
        out.append("            $out.name(").append(key).append(");\n");
        out.append("            ").append(JSONB).append("write(S_").append(property.name).append(", ").append(local).append(", $out);\n");
        if (property.nillable) {
            out.append("        } else {\n            $out.putNull(").append(key).append(");\n");
        }
        out.append("        }\n");
    }

    /** Whether the type is that class, or holds it as a type argument at any depth. */
    private static boolean holds(TypeMirror type, String qualifiedName) {
        if (TypeNames.is(type, qualifiedName)) {
            return true;
        }
        if (type.getKind() == TypeKind.DECLARED) {
            for (TypeMirror argument : ((DeclaredType) type).getTypeArguments()) {
                if (holds(argument, qualifiedName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isEnum(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && ((DeclaredType) type).asElement().getKind() == ElementKind.ENUM;
    }

    /**
     * The name of the tables of an enum the codec writes or reads, wherever it
     * stands: its constants and their names encoded once, {@code C_} and
     * {@code V_}, through which it is written and read with no string. The
     * name is the enum's own, numbered when two enums share it.
     */
    private String enumTables(DeclaredType type) {
        String qualified = TypeNames.qualifiedName(type);
        String tables = enums.get(qualified);
        if (tables == null) {
            String simple = TypeNames.flatName((TypeElement) type.asElement());
            tables = simple;
            for (int n = 2; enums.containsValue(tables); n++) {
                tables = simple + n;
            }
            enums.put(qualified, tables);
        }
        return tables;
    }

    /**
     * Statements writing a member whose value is known not to be null: one
     * {@code put} for a primitive, a boxed primitive, or a string, which the
     * output writes with one check of its state, and the key's name and the
     * value for the rest.
     */
    private String writeMember(String key, TypeMirror type, String expr, @Nullable Property property, int indent) {
        String number = formattedNumber(type, expr, property);
        if (number != null) {
            return "$out.put(" + key + ", " + number + ");";
        }
        boolean fused = switch (type.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> true;
            case DECLARED -> Set.of("java.lang.String", "java.lang.Boolean", "java.lang.Integer", "java.lang.Long",
                    "java.lang.Short", "java.lang.Byte", "java.lang.Double", "java.lang.Float")
                    .contains(TypeNames.qualifiedName((DeclaredType) type));
            default -> false;
        };
        if (fused) {
            return "$out.put(" + key + ", " + expr + ");";
        }
        if (isEnum(type)) {
            return "$out.put(" + key + ", V_" + enumTables((DeclaredType) type) + "[" + expr + ".ordinal()]);";
        }
        return "$out.name(" + key + ");\n" + pad(indent) + writeValue(type, expr, property, indent);
    }

    /** A statement writing a value, known not to be null, of the type as the next value. */
    private String writeValue(TypeMirror type, String expr, @Nullable Property property, int indent) {
        String number = formattedNumber(type, expr, property);
        if (number != null) {
            return "$out.value(" + number + ");";
        }
        return switch (type.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> "$out.value(" + expr + ");";
            case CHAR -> "$out.value(java.lang.String.valueOf(" + expr + "));";
            case DECLARED -> writeDeclared((DeclaredType) type, expr, property, indent);
            case TYPEVAR -> "$" + TypeNames.variable((TypeVariable) type) + ".write(" + expr + ", $out);";
            default -> throw unsupported(type, property);
        };
    }

    /** What the number's kind is for a number format: a primitive's name, a box's primitive, a big number's class; null for another type. */
    private static @Nullable String numberKind(TypeMirror type) {
        return switch (type.getKind()) {
            case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> TypeNames.of(type);
            case DECLARED -> switch (TypeNames.qualifiedName((DeclaredType) type)) {
                case "java.lang.Byte" -> "byte";
                case "java.lang.Short" -> "short";
                case "java.lang.Integer" -> "int";
                case "java.lang.Long" -> "long";
                case "java.lang.Float" -> "float";
                case "java.lang.Double" -> "double";
                case "java.math.BigDecimal", "java.math.BigInteger" -> TypeNames.qualifiedName((DeclaredType) type);
                default -> null;
            };
            default -> null;
        };
    }

    /** An expression of the number's text in the property's format, or null when no format applies. */
    private @Nullable String formattedNumber(TypeMirror type, String expr, @Nullable Property property) {
        String kind = numberKind(type);
        return kind != null ? formattedNumber(kind, expr, property) : null;
    }

    /**
     * The clone is the thread's own, since a {@code DecimalFormat} keeps its
     * work in fields. A float goes through its shortest decimal, as it is
     * written plain, since the double it widens to has digits it never had.
     */
    private @Nullable String formattedNumber(String kind, String expr, @Nullable Property property) {
        if (property == null || property.numberFormat == null) {
            return null;
        }
        formatted.add(property);
        String value = kind.equals("float") ? "new java.math.BigDecimal(java.lang.Float.toString(" + expr + "))" : expr;
        return "((java.text.DecimalFormat) N_" + property.name + ".clone()).format(" + value + ")";
    }

    /** A statement writing a primitive optional's value, its format applied. */
    private String writeOptionalValue(String expr, String accessor, @Nullable Property property) {
        String kind = accessor.substring("getAs".length()).toLowerCase(java.util.Locale.ROOT);
        String number = formattedNumber(kind, expr, property);
        return "$out.value(" + (number != null ? number : expr) + ");";
    }

    private String writeDeclared(DeclaredType type, String expr, @Nullable Property property, int indent) {
        String name = TypeNames.qualifiedName(type);
        switch (name) {
            case "java.lang.String", "java.lang.Boolean", "java.lang.Integer", "java.lang.Long", "java.lang.Short",
                    "java.lang.Byte", "java.lang.Double", "java.lang.Float", "java.math.BigDecimal", "java.math.BigInteger",
                    "net.benelog.silkjson.JsonValue", "net.benelog.silkjson.JsonObject",
                    "net.benelog.silkjson.JsonArray" -> {
                return "$out.value(" + expr + ");";
            }
            case "java.lang.Character", "java.util.UUID", "java.net.URI", "java.time.ZoneId", "java.time.ZoneOffset" -> {
                return "$out.value(" + expr + ".toString());";
            }
            default -> {
                // falls through to the cases below
            }
        }
        if (TIME_PARSED.contains(name) || TIME_PLAIN.contains(name)) {
            if (property != null && property.dateFormat != null && TIME_PARSED.contains(name)) {
                return "$out.value(F_" + property.name + ".format(" + expr + "));";
            }
            if (property != null && property.timeInMillis) {
                return "$out.value(" + epochMillis(name, expr, property) + ");";
            }
            return "$out.value(" + expr + ".toString());";
        }
        Element element = type.asElement();
        if (element.getKind() == ElementKind.ENUM) {
            return "$out.value(V_" + enumTables(type) + "[" + expr + ".ordinal()]);";
        }
        if (name.equals("java.util.Optional")) {
            TypeMirror inner = TypeNames.argument(type, 0);
            return "if (" + expr + ".isPresent()) {\n" + pad(indent + 1) + writeValue(inner, expr + ".get()", property, indent + 1)
                    + "\n" + pad(indent) + "} else {\n" + pad(indent + 1) + "$out.nullValue();\n" + pad(indent) + "}";
        }
        String primitive = optionalAccessor(name);
        if (primitive != null) {
            return "if (" + expr + ".isPresent()) {\n" + pad(indent + 1) + writeOptionalValue(expr + "." + primitive + "()", primitive, property) + "\n"
                    + pad(indent) + "} else {\n" + pad(indent + 1) + "$out.nullValue();\n" + pad(indent) + "}";
        }
        if (LISTS.contains(name) || SETS.contains(name)) {
            return writeCollection(type, expr, property);
        }
        if (name.equals("java.util.Map")) {
            return writeMap(type, expr, property);
        }
        if (Annotations.has(element, Annotations.JSON_BOUND)) {
            return boundCodec(type) + ".write(" + expr + ", $out);";
        }
        throw unsupported(type, property);
    }

    /** An expression of the instant's milliseconds since the epoch; a date or a time with no zone stands in UTC. */
    private String epochMillis(String name, String expr, Property property) {
        if (!TIME_MILLIS.contains(name)) {
            throw millisUnsupported(name, property);
        }
        return switch (name) {
            case "java.time.Instant" -> expr + ".toEpochMilli()";
            case "java.time.LocalDateTime" -> expr + ".toInstant(java.time.ZoneOffset.UTC).toEpochMilli()";
            case "java.time.LocalDate" -> expr + ".atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()";
            default -> expr + ".toInstant().toEpochMilli()";
        };
    }

    /** An expression of the time a number of milliseconds since the epoch is, in UTC for a type with a zone or none. */
    private String fromEpochMillis(String name, Property property) {
        if (!TIME_MILLIS.contains(name)) {
            throw millisUnsupported(name, property);
        }
        String instant = "java.time.Instant.ofEpochMilli($in.readLong())";
        return switch (name) {
            case "java.time.Instant" -> instant;
            case "java.time.ZonedDateTime" -> instant + ".atZone(java.time.ZoneOffset.UTC)";
            case "java.time.OffsetDateTime" -> instant + ".atOffset(java.time.ZoneOffset.UTC)";
            default -> name + ".ofInstant(" + instant + ", java.time.ZoneOffset.UTC)";
        };
    }

    private static Invalid millisUnsupported(String name, Property property) {
        return new Invalid(property.element, "@JsonbDateFormat.TIME_IN_MILLIS takes an instant or a date, not " + name);
    }

    /** The getter of a primitive optional, OptionalInt's getAsInt and the like, or null for another type. */
    private static @Nullable String optionalAccessor(String qualifiedName) {
        return switch (qualifiedName) {
            case "java.util.OptionalInt" -> "getAsInt";
            case "java.util.OptionalLong" -> "getAsLong";
            case "java.util.OptionalDouble" -> "getAsDouble";
            default -> null;
        };
    }

    private String writeCollection(DeclaredType type, String expr, @Nullable Property property) {
        TypeMirror elementType = TypeNames.argument(type, 0);
        // The element's statement first: for a nested collection it appends
        // a helper of its own, which must not land inside this one.
        String written = writeValue(elementType, "$element", property, 4);
        String helper = "write_" + ++helperCount;
        helpers.append(helper()).append("void ").append(helper).append("(").append(TypeNames.of(type))
                .append(" $values, ").append(JSON).append("JsonOutput $out) {\n");
        helpers.append("        $out.array();\n");
        helpers.append("        for (").append(nullableName(elementType)).append("$element : $values) {\n");
        helpers.append("            if ($element == null) {\n                $out.nullValue();\n            } else {\n");
        helpers.append("                ").append(written).append("\n");
        helpers.append("            }\n        }\n        $out.end();\n    }\n\n");
        return helper + "(" + expr + ", $out);";
    }

    private String writeMap(DeclaredType type, String expr, @Nullable Property property) {
        TypeMirror keyType = TypeNames.argument(type, 0);
        if (!TypeNames.is(keyType, "java.lang.String")) {
            throw new Invalid(property != null ? property.element : type.asElement(),
                    "A map bound to a JSON object has String keys, not " + TypeNames.of(keyType));
        }
        TypeMirror valueType = TypeNames.argument(type, 1);
        String written = writeValue(valueType, "$element", property, 4);
        String helper = "write_" + ++helperCount;
        helpers.append(helper()).append("void ").append(helper).append("(").append(TypeNames.of(type))
                .append(" $values, ").append(JSON).append("JsonOutput $out) {\n");
        helpers.append("        $out.object();\n");
        helpers.append("        for (java.util.Map.Entry<java.lang.String, ").append(TypeNames.of(valueType))
                .append("> $entry : $values.entrySet()) {\n");
        helpers.append("            $out.name($entry.getKey());\n");
        helpers.append("            ").append(nullableName(valueType)).append("$element = $entry.getValue();\n");
        helpers.append("            if ($element == null) {\n                $out.nullValue();\n            } else {\n");
        helpers.append("                ").append(written).append("\n");
        helpers.append("            }\n        }\n        $out.end();\n    }\n\n");
        return helper + "(" + expr + ", $out);";
    }

    // ---- read ----

    private void readMethod(StringBuilder out) {
        out.append("    @Override\n");
        out.append("    public ").append(type.targetType()).append(" read(").append(JSON).append("JsonInput $in) {\n");
        Property whole = type.whole;
        if (whole != null && whole.adapter != null && whole.adapted != null) {
            readAdaptedWhole(out, whole, whole.adapted);
            return;
        }
        if (whole != null && whole.deserializer != null) {
            out.append("        return ").append(JSONB).append("read(D_").append(whole.name).append(", $in, ")
                    .append(whole.deserializedClass).append(");\n");
            out.append("    }\n\n");
            return;
        }
        if (!type.subtypes.isEmpty()) {
            readSubtype(out);
            if (type.abstractType()) {
                out.append("    }\n\n");
                return;
            }
        }
        out.append("        $in.object();\n");
        List<Property> readable = type.properties.stream().filter(Property::readable).toList();
        for (Property property : readable) {
            out.append("        ").append(local(property)).append(";\n");
            if (seenFlag(property)) {
                out.append("        boolean s_").append(property.name).append(" = false;\n");
            }
        }
        if (readable.isEmpty()) {
            out.append("        while ($in.nextKey()) {\n            $in.skipValue();\n        }\n");
        } else {
            if (readable.size() <= CHAIN_MAX) {
                // A document written from this declaration has its keys in
                // this order, so they are tried in place one after another
                // first, each read with no switch to go through; the first key
                // out of that order leaves the chain for the loop below.
                out.append("        int $key;\n");
                out.append("        chain: {\n");
                for (Property property : readable) {
                    out.append("            if (!$in.nextKeyIs(K_").append(property.name).append(")) {\n");
                    out.append("                $key = keyIndex($in);\n");
                    out.append("                break chain;\n");
                    out.append("            }\n");
                    StringBuilder body = new StringBuilder();
                    readProperty(body, property);
                    out.append(body.toString().indent(-4));
                }
                out.append("            $key = keyIndex($in);\n");
                out.append("        }\n");
            } else {
                out.append("        int $key = $in.nextKeyIs(K_").append(readable.get(0).name).append(") ? 0 : keyIndex($in);\n");
            }
            // With no chain, a case reads one property and tries the key
            // declared after it in place, which a document written from this
            // declaration has there; any other key goes through keyIndex.
            // After a chain, the loop runs only for a document that has left
            // the declared order once, and goes on out of it: each key goes
            // straight to keyIndex, rather than first failing to be the next
            // one declared.
            boolean predicts = readable.size() > CHAIN_MAX;
            out.append("        while ($key != -1) {\n");
            out.append("            switch ($key) {\n");
            for (int i = 0; i < readable.size(); i++) {
                out.append("                case ").append(i).append(" -> {\n");
                StringBuilder body = new StringBuilder();
                readProperty(body, readable.get(i));
                out.append(body.toString().indent(4));
                if (predicts && i + 1 < readable.size()) {
                    out.append("                    $key = $in.nextKeyIs(K_").append(readable.get(i + 1).name).append(") ? ")
                            .append(i + 1).append(" : keyIndex($in);\n");
                } else {
                    out.append("                    $key = keyIndex($in);\n");
                }
                out.append("                }\n");
            }
            out.append("                default -> {\n");
            out.append("                    $in.skipValue();\n");
            out.append("                    $key = keyIndex($in);\n");
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
            case CONSTRUCTOR -> "new " + type.targetType() + "(" + arguments + ")";
            case FACTORY -> type.targetName() + "." + type.factoryName + "(" + arguments + ")";
            case SETTERS -> "new " + type.targetType() + "()";
        };
        List<Property> set = readable.stream().filter(p -> p.creatorIndex < 0).toList();
        if (set.isEmpty()) {
            out.append("        return ").append(created).append(";\n");
        } else {
            out.append("        ").append(type.targetType()).append(" $result = ").append(created).append(";\n");
            for (Property property : set) {
                out.append("        if (s_").append(property.name).append(") {\n");
                if (property.setter != null) {
                    out.append("            $result.").append(property.setter).append("(p_").append(property.name).append(");\n");
                } else {
                    out.append("            $result.").append(property.name).append(" = p_").append(property.name).append(";\n");
                }
                out.append("        }\n");
            }
            out.append("        return $result;\n");
        }
        out.append("    }\n\n");
    }

    /**
     * The start of a read that a {@code @JsonbTypeInfo} chooses: the key is
     * found wherever it stands, and the subtype's codec reads the object from
     * its opening brace, passing over the key as a member it does not know.
     * A type that can be a value itself reads an object without the key.
     */
    private void readSubtype(StringBuilder out) {
        out.append("        switch ($in.peekOneOf(T_key, T_aliases)) {\n");
        for (int i = 0; i < type.subtypes.size(); i++) {
            out.append("            case ").append(i).append(" -> {\n");
            out.append("                return ").append(boundCodec((DeclaredType) type.subtypes.get(i).type().asType())).append(".read($in);\n");
            out.append("            }\n");
        }
        if (type.abstractType()) {
            out.append("            default -> throw new ").append(JSON).append("JsonException(")
                    .append(literal("Missing key in JSON object: " + type.typeKey)).append(");\n");
        } else {
            out.append("            default -> {\n                // an object of this type itself\n            }\n");
        }
        out.append("        }\n");
    }

    /** The read of a type a {@code @JsonbTypeAdapter} on the type converts: the adapter makes the value of what was read. */
    private void readAdaptedWhole(StringBuilder out, Property whole, TypeMirror adapted) {
        String read = readValue(adapted, null);
        out.append("        ").append(TypeNames.of(adapted)).append(" $adapted = ").append(read).append(";\n");
        out.append("        try {\n");
        out.append("            return A_").append(whole.name).append(".adaptFromJson($adapted);\n");
        out.append("        } catch (java.lang.Exception $e) {\n");
        out.append("            throw new java.lang.IllegalArgumentException($e.getMessage(), $e);\n");
        out.append("        }\n");
        out.append("    }\n\n");
    }

    /**
     * The helper the read goes to for a key out of the declared order: the
     * key's index among the readable properties, -2 for a key it does not
     * know, or -1 at the end of the object. The first four bytes of the member
     * pick the keys that can be there, which are tried in place; a member they
     * pick none for, or written another way, with whitespace or an escape, is
     * scanned and compared with each key.
     */
    private void keyIndexMethod(List<Property> readable) {
        helpers.append("    private static int keyIndex(").append(JSON).append("JsonInput $in) {\n");
        Map<Integer, List<Integer>> heads = readable.size() <= SWITCH_MAX ? heads(readable) : Map.of();
        if (!heads.isEmpty()) {
            helpers.append("        switch ($in.keyHead()) {\n");
            for (Map.Entry<Integer, List<Integer>> head : heads.entrySet()) {
                helpers.append("            case ").append(String.format("0x%08x", head.getKey())).append(" -> {\n");
                for (int i : head.getValue()) {
                    helpers.append("                if ($in.nextKeyIs(K_").append(readable.get(i).name).append(")) {\n");
                    helpers.append("                    return ").append(i).append(";\n                }\n");
                }
                helpers.append("            }\n");
            }
            helpers.append("            default -> {\n                // the end of the object, or a key none of these start with\n            }\n");
            helpers.append("        }\n");
        }
        helpers.append("        if (!$in.nextKey()) {\n            return -1;\n        }\n");
        for (int i = 0; i < readable.size(); i++) {
            helpers.append("        if ($in.keyIs(K_").append(readable.get(i).name).append(")) {\n");
            helpers.append("            return ").append(i).append(";\n        }\n");
        }
        helpers.append("        return -2;\n    }\n\n");
    }

    /**
     * The properties by the first four bytes of their member as the output
     * writes it, in both its forms: the quote and three bytes of the key for
     * the first member, and the comma, the quote, and two bytes for the rest.
     * The two forms never meet, one starting with a quote and the other with a
     * comma, but two keys can share a form's head, and are then tried in
     * declaration order. A key written with an escape, or too short to fill
     * the four bytes, has no head and is left to the scan.
     */
    private static Map<Integer, List<Integer>> heads(List<Property> readable) {
        Map<Integer, List<Integer>> heads = new TreeMap<>();
        for (int i = 0; i < readable.size(); i++) {
            String name = readable.get(i).jsonName;
            if (!plain(name)) {
                continue;
            }
            byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
            byte[] first = new byte[utf8.length + 3];
            first[0] = '"';
            System.arraycopy(utf8, 0, first, 1, utf8.length);
            first[utf8.length + 1] = '"';
            first[utf8.length + 2] = ':';
            if (first.length < 4) {
                continue;
            }
            byte[] next = new byte[first.length + 1];
            next[0] = ',';
            System.arraycopy(first, 0, next, 1, first.length);
            heads.computeIfAbsent(head(first), h -> new ArrayList<>()).add(i);
            heads.computeIfAbsent(head(next), h -> new ArrayList<>()).add(i);
        }
        return heads;
    }

    /** Whether the output writes the name as its own UTF-8, with no escape: no quote, backslash, control character, or surrogate. */
    private static boolean plain(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20 || Character.isSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    /** The first four bytes as one int, the first of them the low byte, as {@code JsonInput.keyHead()} reads them. */
    private static int head(byte[] bytes) {
        return (bytes[0] & 0xFF) | (bytes[1] & 0xFF) << 8 | (bytes[2] & 0xFF) << 16 | (bytes[3] & 0xFF) << 24;
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
        if (property.deserializer != null) {
            String read = JSONB + "read(D_" + property.name + ", $in, " + property.deserializedClass + ")";
            out.append("                ").append(local).append(" = ")
                    .append(property.nullable ? "$in.readNull() ? null : " : "").append(read).append(";\n");
        } else if (property.adapter != null && property.adapted != null) {
            String read = readValue(property.adapted, null);
            if (property.nullable) {
                out.append("                if ($in.readNull()) {\n                    ").append(local).append(" = null;\n");
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
            out.append("                ").append(local).append(" = $in.readNull() ? java.util.Optional.empty() : java.util.Optional.of(")
                    .append(readValue(inner, property)).append(");\n");
        } else if (TypeNames.is(type, "java.util.OptionalInt")) {
            out.append("                ").append(local).append(" = $in.readNull() ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(")
                    .append(readOptionalValue("int", property)).append(");\n");
        } else if (TypeNames.is(type, "java.util.OptionalLong")) {
            out.append("                ").append(local).append(" = $in.readNull() ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(")
                    .append(readOptionalValue("long", property)).append(");\n");
        } else if (TypeNames.is(type, "java.util.OptionalDouble")) {
            out.append("                ").append(local).append(" = $in.readNull() ? java.util.OptionalDouble.empty() : java.util.OptionalDouble.of(")
                    .append(readOptionalValue("double", property)).append(");\n");
        } else if (property.nullable) {
            out.append("                ").append(local).append(" = $in.readNull() ? null : ").append(readValue(type, property)).append(";\n");
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
                + p + "} catch (java.lang.Exception $e) {\n"
                + p + "    throw new java.lang.IllegalArgumentException($e.getMessage(), $e);\n"
                + p + "}\n";
    }

    /** An expression reading a number in the property's format, or null when no format applies. */
    private @Nullable String readFormatted(String kind, @Nullable Property property) {
        if (property == null || property.numberFormat == null) {
            return null;
        }
        formatted.add(property);
        String format = "N_" + property.name;
        return switch (kind) {
            case "byte" -> "(byte) parseWhole(" + format + ", $in, java.lang.Byte.MIN_VALUE, java.lang.Byte.MAX_VALUE)";
            case "short" -> "(short) parseWhole(" + format + ", $in, java.lang.Short.MIN_VALUE, java.lang.Short.MAX_VALUE)";
            case "int" -> "(int) parseWhole(" + format + ", $in, java.lang.Integer.MIN_VALUE, java.lang.Integer.MAX_VALUE)";
            case "long" -> "parseWhole(" + format + ", $in, java.lang.Long.MIN_VALUE, java.lang.Long.MAX_VALUE)";
            case "float" -> "parseNumber(" + format + ", $in).floatValue()";
            case "double" -> "parseNumber(" + format + ", $in).doubleValue()";
            case "java.math.BigDecimal" -> "parseDecimal(" + format + ", $in)";
            default -> "parseInteger(" + format + ", $in)";
        };
    }

    /** An expression reading a primitive optional's value, its format applied. */
    private String readOptionalValue(String kind, @Nullable Property property) {
        String formatted = readFormatted(kind, property);
        if (formatted != null) {
            return formatted;
        }
        return switch (kind) {
            case "int" -> "$in.readInt()";
            case "long" -> "$in.readLong()";
            default -> "$in.readDouble()";
        };
    }

    /** An expression reading a value of the type, which rejects a JSON null itself. */
    private String readValue(TypeMirror type, @Nullable Property property) {
        String kind = numberKind(type);
        String formatted = kind != null ? readFormatted(kind, property) : null;
        if (formatted != null) {
            return formatted;
        }
        return switch (type.getKind()) {
            case BOOLEAN -> "$in.readBoolean()";
            case INT -> "$in.readInt()";
            case LONG -> "$in.readLong()";
            case DOUBLE -> "$in.readDouble()";
            case SHORT -> {
                readShort = true;
                yield "readShort($in)";
            }
            case BYTE -> {
                readByte = true;
                yield "readByte($in)";
            }
            case FLOAT -> "$in.readFloat()";
            case CHAR -> {
                readChar = true;
                yield "readChar($in)";
            }
            case DECLARED -> readDeclared((DeclaredType) type, property);
            case TYPEVAR -> "$" + TypeNames.variable((TypeVariable) type) + ".read($in)";
            default -> throw unsupported(type, property);
        };
    }

    private String readDeclared(DeclaredType type, @Nullable Property property) {
        String name = TypeNames.qualifiedName(type);
        switch (name) {
            case "java.lang.String" -> {
                return "$in.readString()";
            }
            case "java.lang.Boolean" -> {
                return "$in.readBoolean()";
            }
            case "java.lang.Integer" -> {
                return "$in.readInt()";
            }
            case "java.lang.Long" -> {
                return "$in.readLong()";
            }
            case "java.lang.Double" -> {
                return "$in.readDouble()";
            }
            case "java.lang.Short" -> {
                readShort = true;
                return "readShort($in)";
            }
            case "java.lang.Byte" -> {
                readByte = true;
                return "readByte($in)";
            }
            case "java.lang.Float" -> {
                return "$in.readFloat()";
            }
            case "java.lang.Character" -> {
                readChar = true;
                return "readChar($in)";
            }
            case "java.math.BigDecimal" -> {
                return "new java.math.BigDecimal($in.readNumber())";
            }
            case "java.math.BigInteger" -> {
                return "new java.math.BigInteger($in.readNumber())";
            }
            case "java.util.UUID" -> {
                return "java.util.UUID.fromString($in.readString())";
            }
            case "java.net.URI" -> {
                return "java.net.URI.create($in.readString())";
            }
            case "java.time.ZoneId" -> {
                return "java.time.ZoneId.of($in.readString())";
            }
            case "java.time.ZoneOffset" -> {
                return "java.time.ZoneOffset.of($in.readString())";
            }
            case "net.benelog.silkjson.JsonValue" -> {
                return "$in.readValue()";
            }
            case "net.benelog.silkjson.JsonObject" -> {
                return "$in.readValue().asObject()";
            }
            case "net.benelog.silkjson.JsonArray" -> {
                return "$in.readValue().asArray()";
            }
            default -> {
                // falls through to the cases below
            }
        }
        if (TIME_PARSED.contains(name)) {
            if (property != null && property.timeInMillis) {
                return fromEpochMillis(name, property);
            }
            if (property != null && property.dateFormat != null) {
                // Instant has no parse taking a formatter; the formatter's own parse makes one.
                return name.equals("java.time.Instant")
                        ? "F_" + property.name + ".parse($in.readString(), java.time.Instant::from)"
                        : name + ".parse($in.readString(), F_" + property.name + ")";
            }
            return name + ".parse($in.readString())";
        }
        if (TIME_PLAIN.contains(name)) {
            return name + ".parse($in.readString())";
        }
        Element element = type.asElement();
        if (element.getKind() == ElementKind.ENUM) {
            String tables = enumTables(type);
            return "C_" + tables + "[$in.readOneOf(V_" + tables + ")]";
        }
        if (name.equals("java.util.Optional")) {
            TypeMirror inner = TypeNames.argument(type, 0);
            return "($in.readNull() ? java.util.Optional.<" + TypeNames.of(inner) + ">empty() : java.util.Optional.of("
                    + readValue(inner, property) + "))";
        }
        String accessor = optionalAccessor(name);
        if (accessor != null) {
            String read = readOptionalValue(accessor.substring("getAs".length()).toLowerCase(java.util.Locale.ROOT), property);
            return "($in.readNull() ? " + name + ".empty() : " + name + ".of(" + read + "))";
        }
        if (LISTS.contains(name) || SETS.contains(name)) {
            return readCollection(type, SETS.contains(name), property);
        }
        if (name.equals("java.util.Map")) {
            return readMap(type, property);
        }
        if (Annotations.has(element, Annotations.JSON_BOUND)) {
            return boundCodec(type) + ".read($in)";
        }
        throw unsupported(type, property);
    }

    private String readCollection(DeclaredType type, boolean set, @Nullable Property property) {
        TypeMirror elementType = TypeNames.argument(type, 0);
        String helper = "read_" + ++helperCount;
        String declared = TypeNames.of(type);
        String implementation = set ? "java.util.LinkedHashSet<>" : "java.util.ArrayList<>";
        String wrap = set ? "java.util.Collections.unmodifiableSet" : "java.util.Collections.unmodifiableList";
        String element = Annotations.isNullable(List.of(), elementType)
                ? "$in.readNull() ? null : " + readValue(elementType, property)
                : readValue(elementType, property);
        helpers.append(helper()).append(declared).append(" ").append(helper).append("(")
                .append(JSON).append("JsonInput $in) {\n");
        helpers.append("        $in.array();\n");
        helpers.append("        ").append(set ? "java.util.Set<" : "java.util.List<").append(TypeNames.of(elementType))
                .append("> $values = new ").append(implementation).append("();\n");
        helpers.append("        while ($in.nextElement()) {\n");
        helpers.append("            $values.add(").append(element).append(");\n");
        helpers.append("        }\n");
        helpers.append("        return ").append(wrap).append("($values);\n");
        helpers.append("    }\n\n");
        return helper + "($in)";
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
                ? "$in.readNull() ? null : " + readValue(valueType, property)
                : readValue(valueType, property);
        helpers.append(helper()).append(TypeNames.of(type)).append(" ").append(helper).append("(")
                .append(JSON).append("JsonInput $in) {\n");
        helpers.append("        $in.object();\n");
        helpers.append("        java.util.Map<java.lang.String, ").append(TypeNames.of(valueType))
                .append("> $values = new java.util.LinkedHashMap<>();\n");
        helpers.append("        while ($in.nextKey()) {\n");
        helpers.append("            $values.put($in.key(), ").append(element).append(");\n");
        helpers.append("        }\n");
        helpers.append("        return java.util.Collections.unmodifiableMap($values);\n");
        helpers.append("    }\n\n");
        return helper + "($in)";
    }

    // ---- the pieces ----

    /**
     * The codec of a bound type, as an expression: the constant of a plain
     * type, and for a generic one a codec built where it is used, since a
     * constant would tie the static initialization of two codecs together,
     * and two threads could then wait on each other's.
     */
    private String boundCodec(DeclaredType bound) {
        TypeElement element = (TypeElement) bound.asElement();
        String packageName = TypeNames.packageOf(element);
        String name = TypeNames.flatName(element) + "Json";
        String codec = packageName.isEmpty() ? name : packageName + "." + name;
        if (bound.getTypeArguments().isEmpty()) {
            return codec + ".CODEC";
        }
        if (TypeNames.of(bound).equals(type.targetType())) {
            return "this";
        }
        StringBuilder arguments = new StringBuilder();
        for (TypeMirror argument : bound.getTypeArguments()) {
            arguments.append(arguments.length() > 0 ? ", " : "").append(argumentCodec(argument));
        }
        return codec + ".of(" + arguments + ")";
    }

    /** The codec a type argument of a generic type takes: a variable's own, a bound type's, or a helper for any other type. */
    private String argumentCodec(TypeMirror argument) {
        if (argument.getKind() == TypeKind.TYPEVAR) {
            return "$" + TypeNames.variable((TypeVariable) argument);
        }
        if (argument.getKind() == TypeKind.DECLARED && Annotations.has(((DeclaredType) argument).asElement(), Annotations.JSON_BOUND)) {
            return boundCodec((DeclaredType) argument);
        }
        String written = writeValue(argument, "$element", null, 3);
        String read = readValue(argument, null);
        String helper = "codec_" + ++helperCount;
        String name = TypeNames.of(argument);
        helpers.append(helper()).append(JSON).append("JsonCodec<").append(name).append("> ").append(helper).append("() {\n");
        helpers.append("        return ").append(JSON).append("JsonCodec.<").append(name).append(">of(($element, $out) -> {\n");
        helpers.append("            ").append(written).append("\n");
        helpers.append("        }, $in -> ").append(read).append(");\n");
        helpers.append("    }\n\n");
        return helper + "()";
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
        if (!formatted.isEmpty()) {
            numberMethods(sb);
        }
        if (readShort) {
            sb.append("    private static short readShort(").append(JSON).append("JsonInput $in) {\n")
                    .append("        long $value = $in.readLong();\n")
                    .append("        if ($value < java.lang.Short.MIN_VALUE || $value > java.lang.Short.MAX_VALUE) {\n")
                    .append("            throw $in.error(\"Not a 16-bit integer: \" + $value);\n        }\n")
                    .append("        return (short) $value;\n    }\n\n");
        }
        if (readByte) {
            sb.append("    private static byte readByte(").append(JSON).append("JsonInput $in) {\n")
                    .append("        long $value = $in.readLong();\n")
                    .append("        if ($value < java.lang.Byte.MIN_VALUE || $value > java.lang.Byte.MAX_VALUE) {\n")
                    .append("            throw $in.error(\"Not an 8-bit integer: \" + $value);\n        }\n")
                    .append("        return (byte) $value;\n    }\n\n");
        }
        if (readChar) {
            sb.append("    private static char readChar(").append(JSON).append("JsonInput $in) {\n")
                    .append("        java.lang.String $value = $in.readString();\n")
                    .append("        if ($value.length() != 1) {\n")
                    .append("            throw $in.error(\"Not a single character\");\n        }\n")
                    .append("        return $value.charAt(0);\n    }\n\n");
        }
        return sb.toString();
    }

    /**
     * The format of a {@code @JsonbNumberFormat}, and its reads: a string that
     * is the number whole, read as exactly as a {@code BigDecimal} holds it.
     * The prototype is only ever cloned, so each read has a format of its own.
     */
    private static void numberMethods(StringBuilder sb) {
        sb.append("    private static java.text.DecimalFormat numberFormat(java.lang.String $pattern, java.util.Locale $locale) {\n")
                .append("        java.text.DecimalFormat $format = new java.text.DecimalFormat($pattern,")
                .append(" java.text.DecimalFormatSymbols.getInstance($locale));\n")
                .append("        $format.setParseBigDecimal(true);\n")
                .append("        return $format;\n    }\n\n");
        sb.append("    private static java.lang.Number parseNumber(java.text.DecimalFormat $format, ").append(JSON).append("JsonInput $in) {\n")
                .append("        java.lang.String $text = $in.readString();\n")
                .append("        java.text.ParsePosition $position = new java.text.ParsePosition(0);\n")
                .append("        java.lang.Number $number = ((java.text.DecimalFormat) $format.clone()).parse($text, $position);\n")
                .append("        if ($number == null || $position.getIndex() != $text.length()) {\n")
                .append("            throw $in.error(\"Not a number in the format \" + $format.toPattern());\n        }\n")
                .append("        return $number;\n    }\n\n");
        sb.append("    private static java.math.BigDecimal parseDecimal(java.text.DecimalFormat $format, ").append(JSON).append("JsonInput $in) {\n")
                .append("        if (parseNumber($format, $in) instanceof java.math.BigDecimal $decimal) {\n")
                .append("            return $decimal;\n        }\n")
                .append("        throw $in.error(\"Not a finite number\");\n    }\n\n");
        sb.append("    private static java.math.BigInteger parseInteger(java.text.DecimalFormat $format, ").append(JSON).append("JsonInput $in) {\n")
                .append("        try {\n")
                .append("            return parseDecimal($format, $in).toBigIntegerExact();\n")
                .append("        } catch (java.lang.ArithmeticException $e) {\n")
                .append("            throw $in.error(\"Not a whole number\");\n        }\n    }\n\n");
        sb.append("    private static long parseWhole(java.text.DecimalFormat $format, ").append(JSON)
                .append("JsonInput $in, long $min, long $max) {\n")
                .append("        java.math.BigDecimal $decimal = parseDecimal($format, $in);\n")
                .append("        try {\n")
                .append("            long $value = $decimal.longValueExact();\n")
                .append("            if ($value >= $min && $value <= $max) {\n")
                .append("                return $value;\n            }\n")
                .append("        } catch (java.lang.ArithmeticException $e) {\n")
                .append("            // a fraction, or too large for a long: refused below\n")
                .append("        }\n")
                .append("        throw $in.error(\"Not a whole number in range\");\n    }\n\n");
    }
}
