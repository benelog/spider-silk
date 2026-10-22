package net.benelog.silkjson.jsonp;

import jakarta.json.JsonString;

import org.jspecify.annotations.Nullable;

/** A JSON string, equal to any {@link JsonString} of the same characters. */
final class SilkJsonString implements JsonString {

    private final String value;

    SilkJsonString(String value) {
        this.value = value;
    }

    @Override
    public String getString() {
        return value;
    }

    @Override
    public CharSequence getChars() {
        return value;
    }

    @Override
    public ValueType getValueType() {
        return ValueType.STRING;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof JsonString string && value.equals(string.getString()));
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** The string as a JSON literal, quoted and escaped. */
    @Override
    public String toString() {
        return Values.toJson(this);
    }
}
