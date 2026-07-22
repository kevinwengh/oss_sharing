/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

/** Immutable expression runtime shared by generated raw-string transformers. */
public final class JsonExpressionRuntime {
  private static final MathContext MATH_CONTEXT = MathContext.DECIMAL128;
  private final Map<String, FunctionDefinition> functions;

  private JsonExpressionRuntime(Map<String, FunctionDefinition> functions) {
    this.functions = Map.copyOf(functions);
  }

  public static JsonExpressionRuntime standard() {
    return builder().withStandardFunctions().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public JsonValue text(String value) {
    return JsonValue.text(value);
  }

  public JsonValue number(String value) {
    try {
      return JsonValue.number(value);
    } catch (NumberFormatException e) {
      throw new QueryEvaluationException("Invalid generated numeric literal: " + value, e);
    }
  }

  public JsonValue bool(boolean value) {
    return JsonValue.bool(value);
  }

  public JsonValue nullValue() {
    return JsonValue.nullValue();
  }

  public JsonValue add(JsonValue left, JsonValue right) {
    return numericBinary("+", left, right, BigDecimal::add);
  }

  public JsonValue subtract(JsonValue left, JsonValue right) {
    return numericBinary("-", left, right, BigDecimal::subtract);
  }

  public JsonValue multiply(JsonValue left, JsonValue right) {
    return numericBinary("*", left, right, BigDecimal::multiply);
  }

  public JsonValue divide(JsonValue left, JsonValue right) {
    BigDecimal divisor = requireNumber(right, "/");
    if (divisor.compareTo(BigDecimal.ZERO) == 0) {
      throw new QueryEvaluationException("Division by zero");
    }
    return JsonValue.number(requireNumber(left, "/").divide(divisor, MATH_CONTEXT));
  }

  public JsonValue modulo(JsonValue left, JsonValue right) {
    BigDecimal divisor = requireNumber(right, "%");
    if (divisor.compareTo(BigDecimal.ZERO) == 0) {
      throw new QueryEvaluationException("Modulo by zero");
    }
    return JsonValue.number(requireNumber(left, "%").remainder(divisor, MATH_CONTEXT));
  }

  public JsonValue negate(JsonValue value) {
    return isNull(value)
        ? nullValue()
        : JsonValue.number(requireNumber(value, "unary -").negate(MATH_CONTEXT));
  }

  public JsonValue equal(JsonValue left, JsonValue right) {
    if (isNull(left) || isNull(right)) {
      return nullValue();
    }
    return bool(left.equals(right));
  }

  public JsonValue notEqual(JsonValue left, JsonValue right) {
    return not(equal(left, right));
  }

  public JsonValue lessThan(JsonValue left, JsonValue right) {
    return compare(left, right, comparison -> comparison < 0);
  }

  public JsonValue lessThanOrEqual(JsonValue left, JsonValue right) {
    return compare(left, right, comparison -> comparison <= 0);
  }

  public JsonValue greaterThan(JsonValue left, JsonValue right) {
    return compare(left, right, comparison -> comparison > 0);
  }

  public JsonValue greaterThanOrEqual(JsonValue left, JsonValue right) {
    return compare(left, right, comparison -> comparison >= 0);
  }

  public JsonValue and(JsonValue left, JsonValue right) {
    Boolean first = nullableBoolean(left, "AND");
    Boolean second = nullableBoolean(right, "AND");
    if (Boolean.FALSE.equals(first) || Boolean.FALSE.equals(second)) {
      return bool(false);
    }
    return first == null || second == null ? nullValue() : bool(true);
  }

  public JsonValue or(JsonValue left, JsonValue right) {
    Boolean first = nullableBoolean(left, "OR");
    Boolean second = nullableBoolean(right, "OR");
    if (Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second)) {
      return bool(true);
    }
    return first == null || second == null ? nullValue() : bool(false);
  }

  public JsonValue not(JsonValue value) {
    Boolean booleanValue = nullableBoolean(value, "NOT");
    return booleanValue == null ? nullValue() : bool(!booleanValue);
  }

  public boolean isTrue(JsonValue value) {
    return Boolean.TRUE.equals(nullableBoolean(value, "WHERE"));
  }

  public JsonValue upper(JsonValue value) {
    return isNull(value) ? nullValue() : text(requireText("UPPER", value).toUpperCase(Locale.ROOT));
  }

  public JsonValue lower(JsonValue value) {
    return isNull(value) ? nullValue() : text(requireText("LOWER", value).toLowerCase(Locale.ROOT));
  }

  public JsonValue concat(JsonValue... values) {
    StringBuilder result = new StringBuilder();
    for (JsonValue value : values) {
      if (isNull(value)) {
        return nullValue();
      }
      result.append(requireText("CONCAT", value));
    }
    return text(result.toString());
  }

  public JsonValue coalesce(JsonValue... values) {
    for (JsonValue value : values) {
      if (!isNull(value)) {
        return value;
      }
    }
    return nullValue();
  }

  public JsonValue length(JsonValue value) {
    if (isNull(value)) {
      return nullValue();
    }
    int size =
        switch (value) {
          case JsonValue.StringValue string -> string.value().length();
          case JsonValue.ArrayValue array -> array.values().size();
          case JsonValue.ObjectValue object -> object.fields().size();
          default ->
              throw new QueryEvaluationException("LENGTH requires a string, array, or object");
        };
    return JsonValue.number(BigDecimal.valueOf(size));
  }

  public JsonValue abs(JsonValue value) {
    return isNull(value)
        ? nullValue()
        : JsonValue.number(requireNumber(value, "ABS").abs(MATH_CONTEXT));
  }

  public JsonValue round(JsonValue value) {
    return round(value, JsonValue.number(BigDecimal.ZERO));
  }

  public JsonValue round(JsonValue value, JsonValue scaleValue) {
    if (isNull(value)) {
      return nullValue();
    }
    int scale = requireInteger("ROUND scale", scaleValue);
    return JsonValue.number(requireNumber(value, "ROUND").setScale(scale, RoundingMode.HALF_UP));
  }

  public JsonValue call0(String name) {
    return invoke(name, List.of());
  }

  public JsonValue call1(String name, JsonValue first) {
    return invoke(name, List.of(first));
  }

  public JsonValue call2(String name, JsonValue first, JsonValue second) {
    return invoke(name, List.of(first, second));
  }

  public JsonValue call3(String name, JsonValue first, JsonValue second, JsonValue third) {
    return invoke(name, List.of(first, second, third));
  }

  public JsonValue callList(String name, List<JsonValue> arguments) {
    return invoke(name, List.copyOf(arguments));
  }

  public boolean canInvokeStandardFunction(String name) {
    FunctionDefinition definition = functions.get(normalizeName(name));
    return definition != null && definition.standard();
  }

  public void validateFunction(String name, int argumentCount) {
    FunctionDefinition definition = functions.get(normalizeName(name));
    if (definition == null) {
      throw new QueryParseException("Unknown function: " + name);
    }
    definition.validateArity(argumentCount);
  }

  public String writeObject1(String[] prefixes, JsonValue first) {
    StringBuilder output = new StringBuilder(64);
    output.append('{').append(prefixes[0]);
    first.appendJson(output);
    return output.append('}').toString();
  }

  public String writeObject2(String[] prefixes, JsonValue first, JsonValue second) {
    StringBuilder output = new StringBuilder(96);
    output.append('{').append(prefixes[0]);
    first.appendJson(output);
    output.append(',').append(prefixes[1]);
    second.appendJson(output);
    return output.append('}').toString();
  }

  public String writeObject3(
      String[] prefixes, JsonValue first, JsonValue second, JsonValue third) {
    StringBuilder output = new StringBuilder(128);
    output.append('{').append(prefixes[0]);
    first.appendJson(output);
    output.append(',').append(prefixes[1]);
    second.appendJson(output);
    output.append(',').append(prefixes[2]);
    third.appendJson(output);
    return output.append('}').toString();
  }

  public String writeObject(String[] prefixes, JsonValue[] values) {
    StringBuilder output = new StringBuilder(64 + prefixes.length * 16);
    output.append('{');
    for (int index = 0; index < prefixes.length; index++) {
      if (index != 0) {
        output.append(',');
      }
      output.append(prefixes[index]);
      Objects.requireNonNull(values[index], "generated expression result").appendJson(output);
    }
    return output.append('}').toString();
  }

  private JsonValue invoke(String name, List<JsonValue> arguments) {
    FunctionDefinition definition = functions.get(normalizeName(name));
    if (definition == null) {
      throw new QueryEvaluationException("Unknown function: " + name);
    }
    definition.validateArity(arguments.size());
    return Objects.requireNonNull(
        definition.function().apply(arguments),
        () -> "Function " + name + " returned Java null; return JsonValue.nullValue() instead");
  }

  private JsonValue numericBinary(
      String operator, JsonValue left, JsonValue right, DecimalOperator operation) {
    if (isNull(left) || isNull(right)) {
      return nullValue();
    }
    return JsonValue.number(
        operation.apply(
            requireNumber(left, operator), requireNumber(right, operator), MATH_CONTEXT));
  }

  private JsonValue compare(JsonValue left, JsonValue right, IntPredicate predicate) {
    if (isNull(left) || isNull(right)) {
      return nullValue();
    }
    int comparison =
        switch (left) {
          case JsonValue.NumberValue first when right instanceof JsonValue.NumberValue second ->
              first.value().compareTo(second.value());
          case JsonValue.StringValue first when right instanceof JsonValue.StringValue second ->
              first.value().compareTo(second.value());
          case JsonValue.BooleanValue first when right instanceof JsonValue.BooleanValue second ->
              Boolean.compare(first.value(), second.value());
          default ->
              throw new QueryEvaluationException(
                  "Cannot compare "
                      + left.type().name().toLowerCase(Locale.ROOT)
                      + " with "
                      + right.type().name().toLowerCase(Locale.ROOT));
        };
    return bool(predicate.test(comparison));
  }

  private static BigDecimal requireNumber(JsonValue value, String operator) {
    if (value instanceof JsonValue.NumberValue number) {
      return number.value();
    }
    throw new QueryEvaluationException(
        "Operator " + operator + " requires numbers but received " + typeName(value));
  }

  private static String requireText(String function, JsonValue value) {
    if (value instanceof JsonValue.StringValue string) {
      return string.value();
    }
    throw new QueryEvaluationException(function + " requires text but received " + typeName(value));
  }

  private static int requireInteger(String name, JsonValue value) {
    try {
      return requireNumber(value, name).intValueExact();
    } catch (ArithmeticException e) {
      throw new QueryEvaluationException(name + " must be an integer", e);
    }
  }

  private static Boolean nullableBoolean(JsonValue value, String operator) {
    if (isNull(value)) {
      return null;
    }
    if (value instanceof JsonValue.BooleanValue booleanValue) {
      return booleanValue.value();
    }
    throw new QueryEvaluationException(
        operator + " requires a boolean but received " + typeName(value));
  }

  private static boolean isNull(JsonValue value) {
    return value == null || value.isNull();
  }

  private static String typeName(JsonValue value) {
    return value == null ? "Java null" : value.type().name().toLowerCase(Locale.ROOT);
  }

  private static String normalizeName(String name) {
    return Objects.requireNonNull(name, "name").toUpperCase(Locale.ROOT);
  }

  @FunctionalInterface
  private interface DecimalOperator {
    BigDecimal apply(BigDecimal left, BigDecimal right, MathContext context);
  }

  private record FunctionDefinition(
      String name,
      int minimumArguments,
      int maximumArguments,
      JsonFunction function,
      boolean standard) {
    private void validateArity(int count) {
      if (count < minimumArguments || count > maximumArguments) {
        String expected =
            minimumArguments == maximumArguments
                ? Integer.toString(minimumArguments)
                : minimumArguments + ".." + maximumArguments;
        throw new QueryParseException(
            name + " expects " + expected + " arguments but received " + count);
      }
    }
  }

  /** Builds an immutable runtime and function registry. */
  public static final class Builder {
    private final Map<String, FunctionDefinition> functions = new LinkedHashMap<>();

    public Builder withStandardFunctions() {
      standard("UPPER", 1, 1, arguments -> standardRuntime().upper(arguments.getFirst()));
      standard("LOWER", 1, 1, arguments -> standardRuntime().lower(arguments.getFirst()));
      standard(
          "CONCAT",
          2,
          Integer.MAX_VALUE,
          arguments -> standardRuntime().concat(arguments.toArray(JsonValue[]::new)));
      standard(
          "COALESCE",
          1,
          Integer.MAX_VALUE,
          arguments -> standardRuntime().coalesce(arguments.toArray(JsonValue[]::new)));
      standard("LENGTH", 1, 1, arguments -> standardRuntime().length(arguments.getFirst()));
      standard("ABS", 1, 1, arguments -> standardRuntime().abs(arguments.getFirst()));
      standard(
          "ROUND",
          1,
          2,
          arguments ->
              arguments.size() == 1
                  ? standardRuntime().round(arguments.getFirst())
                  : standardRuntime().round(arguments.get(0), arguments.get(1)));
      return this;
    }

    public Builder function(String name, int arity, JsonFunction function) {
      return function(name, arity, arity, function);
    }

    public Builder function(
        String name, int minimumArguments, int maximumArguments, JsonFunction function) {
      register(name, minimumArguments, maximumArguments, function, false);
      return this;
    }

    public JsonExpressionRuntime build() {
      return new JsonExpressionRuntime(functions);
    }

    private void standard(String name, int minimum, int maximum, JsonFunction function) {
      register(name, minimum, maximum, function, true);
    }

    private void register(
        String name, int minimum, int maximum, JsonFunction function, boolean standard) {
      String normalized = normalizeName(name);
      if (minimum < 0 || maximum < minimum) {
        throw new IllegalArgumentException("Invalid arity range for " + normalized);
      }
      functions.put(
          normalized,
          new FunctionDefinition(
              normalized,
              minimum,
              maximum,
              Objects.requireNonNull(function, "function"),
              standard));
    }

    private JsonExpressionRuntime standardRuntime() {
      return new JsonExpressionRuntime(Map.of());
    }
  }
}
