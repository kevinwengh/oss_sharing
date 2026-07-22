/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Minimal JSON value model used only for fields selected by a compiled query. */
public sealed interface JsonValue
    permits JsonValue.NullValue,
        JsonValue.BooleanValue,
        JsonValue.NumberValue,
        JsonValue.StringValue,
        JsonValue.ArrayValue,
        JsonValue.ObjectValue {

  enum Type {
    NULL,
    BOOLEAN,
    NUMBER,
    STRING,
    ARRAY,
    OBJECT
  }

  Type type();

  void appendJson(StringBuilder output);

  default boolean isNull() {
    return type() == Type.NULL;
  }

  static JsonValue nullValue() {
    return NullValue.INSTANCE;
  }

  static JsonValue bool(boolean value) {
    return value ? BooleanValue.TRUE : BooleanValue.FALSE;
  }

  static JsonValue number(String lexicalValue) {
    if (!isJsonNumber(lexicalValue)) {
      throw new NumberFormatException("Invalid JSON number: " + lexicalValue);
    }
    return new NumberValue(lexicalValue, new BigDecimal(lexicalValue));
  }

  static JsonValue number(BigDecimal value) {
    Objects.requireNonNull(value, "value");
    return new NumberValue(value.toString(), value);
  }

  static JsonValue text(String value) {
    return new StringValue(Objects.requireNonNull(value, "value"));
  }

  static JsonValue array(List<JsonValue> values) {
    return new ArrayValue(values);
  }

  static JsonValue object(Map<String, JsonValue> fields) {
    return new ObjectValue(fields);
  }

  /** Singleton JSON null. */
  final class NullValue implements JsonValue {
    private static final NullValue INSTANCE = new NullValue();

    private NullValue() {}

    @Override
    public Type type() {
      return Type.NULL;
    }

    @Override
    public void appendJson(StringBuilder output) {
      output.append("null");
    }

    @Override
    public String toString() {
      return "null";
    }
  }

  /** Allocation-free boolean values. */
  final class BooleanValue implements JsonValue {
    private static final BooleanValue TRUE = new BooleanValue(true);
    private static final BooleanValue FALSE = new BooleanValue(false);
    private final boolean value;

    private BooleanValue(boolean value) {
      this.value = value;
    }

    public boolean value() {
      return value;
    }

    @Override
    public Type type() {
      return Type.BOOLEAN;
    }

    @Override
    public void appendJson(StringBuilder output) {
      output.append(value);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof BooleanValue that && value == that.value;
    }

    @Override
    public int hashCode() {
      return Boolean.hashCode(value);
    }
  }

  /** A number retains its input spelling while exposing exact decimal arithmetic. */
  final class NumberValue implements JsonValue {
    private final String lexicalValue;
    private final BigDecimal value;

    private NumberValue(String lexicalValue, BigDecimal value) {
      this.lexicalValue = Objects.requireNonNull(lexicalValue, "lexicalValue");
      this.value = Objects.requireNonNull(value, "value");
    }

    public String lexicalValue() {
      return lexicalValue;
    }

    public BigDecimal value() {
      return value;
    }

    @Override
    public Type type() {
      return Type.NUMBER;
    }

    @Override
    public void appendJson(StringBuilder output) {
      output.append(lexicalValue);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof NumberValue that && value.compareTo(that.value) == 0;
    }

    @Override
    public int hashCode() {
      return value.stripTrailingZeros().hashCode();
    }
  }

  record StringValue(String value) implements JsonValue {
    public StringValue {
      Objects.requireNonNull(value, "value");
    }

    @Override
    public Type type() {
      return Type.STRING;
    }

    @Override
    public void appendJson(StringBuilder output) {
      appendQuoted(output, value);
    }
  }

  record ArrayValue(List<JsonValue> values) implements JsonValue {
    public ArrayValue {
      values = List.copyOf(values);
    }

    /** Returns the element at {@code index}, or JSON null when the index is out of range. */
    public JsonValue get(int index) {
      return index >= 0 && index < values.size() ? values.get(index) : NullValue.INSTANCE;
    }

    @Override
    public Type type() {
      return Type.ARRAY;
    }

    @Override
    public void appendJson(StringBuilder output) {
      output.append('[');
      for (int index = 0; index < values.size(); index++) {
        if (index != 0) {
          output.append(',');
        }
        values.get(index).appendJson(output);
      }
      output.append(']');
    }
  }

  final class ObjectValue implements JsonValue {
    private final Map<String, JsonValue> fields;

    private ObjectValue(Map<String, JsonValue> fields) {
      this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public Map<String, JsonValue> fields() {
      return fields;
    }

    public JsonValue get(String name) {
      return fields.getOrDefault(name, NullValue.INSTANCE);
    }

    @Override
    public Type type() {
      return Type.OBJECT;
    }

    @Override
    public void appendJson(StringBuilder output) {
      output.append('{');
      boolean first = true;
      for (Map.Entry<String, JsonValue> field : fields.entrySet()) {
        if (!first) {
          output.append(',');
        }
        first = false;
        appendQuoted(output, field.getKey());
        output.append(':');
        field.getValue().appendJson(output);
      }
      output.append('}');
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof ObjectValue that && fields.equals(that.fields);
    }

    @Override
    public int hashCode() {
      return fields.hashCode();
    }
  }

  static List<JsonValue> mutableArray() {
    return new ArrayList<>();
  }

  static Map<String, JsonValue> mutableObject() {
    return new LinkedHashMap<>();
  }

  static void appendQuoted(StringBuilder output, String value) {
    output.append('"');
    int start = 0;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      String escape =
          switch (character) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\b' -> "\\b";
            case '\f' -> "\\f";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> null;
          };
      boolean unpairedSurrogate =
          Character.isSurrogate(character)
              && !((Character.isHighSurrogate(character)
                      && index + 1 < value.length()
                      && Character.isLowSurrogate(value.charAt(index + 1)))
                  || (Character.isLowSurrogate(character)
                      && index > 0
                      && Character.isHighSurrogate(value.charAt(index - 1))));
      if (escape != null || character < 0x20 || unpairedSurrogate) {
        output.append(value, start, index);
        if (escape != null) {
          output.append(escape);
        } else {
          appendUnicodeEscape(output, character);
        }
        start = index + 1;
      }
    }
    output.append(value, start, value.length()).append('"');
  }

  private static void appendUnicodeEscape(StringBuilder output, char character) {
    output.append("\\u");
    output.append(Character.forDigit((character >>> 12) & 0x0f, 16));
    output.append(Character.forDigit((character >>> 8) & 0x0f, 16));
    output.append(Character.forDigit((character >>> 4) & 0x0f, 16));
    output.append(Character.forDigit(character & 0x0f, 16));
  }

  private static boolean isJsonNumber(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    int index = value.charAt(0) == '-' ? 1 : 0;
    if (index == value.length()) {
      return false;
    }
    if (value.charAt(index) == '0') {
      index++;
      if (index < value.length() && isDigit(value.charAt(index))) {
        return false;
      }
    } else {
      int start = index;
      while (index < value.length() && isDigit(value.charAt(index))) {
        index++;
      }
      if (start == index || value.charAt(start) == '0') {
        return false;
      }
    }
    if (index < value.length() && value.charAt(index) == '.') {
      int fraction = ++index;
      while (index < value.length() && isDigit(value.charAt(index))) {
        index++;
      }
      if (fraction == index) {
        return false;
      }
    }
    if (index < value.length() && (value.charAt(index) == 'e' || value.charAt(index) == 'E')) {
      index++;
      if (index < value.length() && (value.charAt(index) == '+' || value.charAt(index) == '-')) {
        index++;
      }
      int exponent = index;
      while (index < value.length() && isDigit(value.charAt(index))) {
        index++;
      }
      if (exponent == index) {
        return false;
      }
    }
    return index == value.length();
  }

  private static boolean isDigit(char value) {
    return value >= '0' && value <= '9';
  }
}
