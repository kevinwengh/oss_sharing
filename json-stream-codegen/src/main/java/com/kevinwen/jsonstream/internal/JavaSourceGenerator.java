/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.kevinwen.jsonstream.JsonExpressionRuntime;
import com.kevinwen.jsonstream.internal.QueryModel.Binary;
import com.kevinwen.jsonstream.internal.QueryModel.Expression;
import com.kevinwen.jsonstream.internal.QueryModel.Field;
import com.kevinwen.jsonstream.internal.QueryModel.FunctionCall;
import com.kevinwen.jsonstream.internal.QueryModel.Literal;
import com.kevinwen.jsonstream.internal.QueryModel.PathStep;
import com.kevinwen.jsonstream.internal.QueryModel.Projection;
import com.kevinwen.jsonstream.internal.QueryModel.Query;
import com.kevinwen.jsonstream.internal.QueryModel.Unary;

/** Converts a validated query into a stateless raw-string transformer class. */
public final class JavaSourceGenerator {
  private static final String GENERATED_PACKAGE = "com.kevinwen.jsonstream.generated";

  private final JsonExpressionRuntime runtime;

  public JavaSourceGenerator(JsonExpressionRuntime runtime) {
    this.runtime = runtime;
  }

  public GeneratedSource generate(Query query, String canonicalQuery) {
    validateFunctions(query);
    Map<List<PathStep>, Integer> paths = collectPaths(query);
    Map<Literal, String> literals = collectLiterals(query);
    Map<Expression, String> predicateLocals =
        query.predicate() == null ? Map.of() : collectCommonExpressions(List.of(query.predicate()));
    Map<Expression, String> projectionLocals =
        collectCommonExpressions(query.projections().stream().map(Projection::expression).toList());
    String simpleName = "GeneratedStringTransform_" + hash(canonicalQuery);
    String className = GENERATED_PACKAGE + "." + simpleName;
    StringBuilder source = new StringBuilder(2_048);
    source
        .append("package ")
        .append(GENERATED_PACKAGE)
        .append(";\n\n")
        .append("import com.kevinwen.jsonstream.JsonExpressionRuntime;\n")
        .append("import com.kevinwen.jsonstream.JsonValue;\n")
        .append("import com.kevinwen.jsonstream.JsonTransformer;\n")
        .append("import com.kevinwen.jsonstream.internal.SelectiveJsonReader;\n")
        .append("import java.util.List;\n")
        .append("import java.util.Objects;\n\n")
        .append("public final class ")
        .append(simpleName)
        .append(" implements JsonTransformer {\n")
        .append("  private static final String[] OUTPUT_PREFIXES = new String[] {")
        .append(
            query.projections().stream()
                .map(projection -> javaString("\"" + projection.alias() + "\":"))
                .collect(Collectors.joining(", ")))
        .append("};\n")
        .append("  private final JsonExpressionRuntime runtime;\n")
        .append("  private final SelectiveJsonReader reader;\n");
    literals
        .values()
        .forEach(name -> source.append("  private final JsonValue ").append(name).append(";\n"));
    source
        .append("\n  public ")
        .append(simpleName)
        .append("(JsonExpressionRuntime runtime, SelectiveJsonReader reader) {\n")
        .append("    this.runtime = Objects.requireNonNull(runtime, \"runtime\");\n")
        .append("    this.reader = Objects.requireNonNull(reader, \"reader\");\n");
    literals.forEach(
        (literal, name) ->
            source
                .append("    this.")
                .append(name)
                .append(" = ")
                .append(emitRuntimeLiteral(literal))
                .append(";\n"));
    source
        .append("  }\n\n")
        .append("  @Override\n")
        .append("  public String transform(String input) {\n")
        .append("    Objects.requireNonNull(input, \"input\");\n")
        .append("    JsonValue[] fields = reader.read(input);\n");
    if (query.predicate() != null) {
      emitLocalDeclarations(source, predicateLocals, paths, literals);
      source
          .append("    if (!runtime.isTrue(")
          .append(emitExpression(query.predicate(), paths, literals, predicateLocals))
          .append(")) {\n      return null;\n    }\n");
    }
    emitLocalDeclarations(source, projectionLocals, paths, literals);
    if (query.projections().size() <= 3) {
      source
          .append("    return runtime.writeObject")
          .append(query.projections().size())
          .append("(OUTPUT_PREFIXES");
      for (Projection projection : query.projections()) {
        source
            .append(", ")
            .append(emitExpression(projection.expression(), paths, literals, projectionLocals));
      }
      source.append(");\n");
    } else {
      source
          .append("    JsonValue[] output = new JsonValue[")
          .append(query.projections().size())
          .append("];\n");
      for (int index = 0; index < query.projections().size(); index++) {
        source
            .append("    output[")
            .append(index)
            .append("] = ")
            .append(
                emitExpression(
                    query.projections().get(index).expression(), paths, literals, projectionLocals))
            .append(";\n");
      }
      source.append("    return runtime.writeObject(OUTPUT_PREFIXES, output);\n");
    }
    source.append("  }\n}\n");
    return new GeneratedSource(className, source.toString(), List.copyOf(paths.keySet()));
  }

  private String emitExpression(
      Expression expression,
      Map<List<PathStep>, Integer> paths,
      Map<Literal, String> literals,
      Map<Expression, String> commonExpressions) {
    String local = commonExpressions.get(expression);
    if (local != null) {
      return local;
    }
    return switch (expression) {
      case Literal literal -> literals.get(literal);
      case Field field -> "fields[" + paths.get(field.path()) + "]";
      case Unary unary -> emitUnary(unary, paths, literals, commonExpressions);
      case Binary binary -> emitBinary(binary, paths, literals, commonExpressions);
      case FunctionCall function -> emitFunction(function, paths, literals, commonExpressions);
    };
  }

  private String emitUnary(
      Unary unary,
      Map<List<PathStep>, Integer> paths,
      Map<Literal, String> literals,
      Map<Expression, String> commonExpressions) {
    String operand = emitExpression(unary.operand(), paths, literals, commonExpressions);
    return switch (unary.operator()) {
      case "+" -> operand;
      case "-" -> "runtime.negate(" + operand + ")";
      case "NOT" -> "runtime.not(" + operand + ")";
      default -> throw new IllegalStateException("Unexpected unary operator " + unary.operator());
    };
  }

  private String emitBinary(
      Binary binary,
      Map<List<PathStep>, Integer> paths,
      Map<Literal, String> literals,
      Map<Expression, String> commonExpressions) {
    String method =
        switch (binary.operator()) {
          case "+" -> "add";
          case "-" -> "subtract";
          case "*" -> "multiply";
          case "/" -> "divide";
          case "%" -> "modulo";
          case "=" -> "equal";
          case "!=" -> "notEqual";
          case "<" -> "lessThan";
          case "<=" -> "lessThanOrEqual";
          case ">" -> "greaterThan";
          case ">=" -> "greaterThanOrEqual";
          case "AND" -> "and";
          case "OR" -> "or";
          default ->
              throw new IllegalStateException("Unexpected binary operator " + binary.operator());
        };
    return "runtime."
        + method
        + "("
        + emitExpression(binary.left(), paths, literals, commonExpressions)
        + ", "
        + emitExpression(binary.right(), paths, literals, commonExpressions)
        + ")";
  }

  private String emitFunction(
      FunctionCall function,
      Map<List<PathStep>, Integer> paths,
      Map<Literal, String> literals,
      Map<Expression, String> commonExpressions) {
    String arguments =
        function.arguments().stream()
            .map(argument -> emitExpression(argument, paths, literals, commonExpressions))
            .collect(Collectors.joining(", "));
    if (runtime.canInvokeStandardFunction(function.name())) {
      String method = function.name().toLowerCase(java.util.Locale.ROOT);
      return "runtime." + method + "(" + arguments + ")";
    }
    return switch (function.arguments().size()) {
      case 0 -> "runtime.call0(" + javaString(function.name()) + ")";
      case 1 -> "runtime.call1(" + javaString(function.name()) + ", " + arguments + ")";
      case 2 -> "runtime.call2(" + javaString(function.name()) + ", " + arguments + ")";
      case 3 -> "runtime.call3(" + javaString(function.name()) + ", " + arguments + ")";
      default ->
          "runtime.callList(" + javaString(function.name()) + ", List.of(" + arguments + "))";
    };
  }

  private String emitRuntimeLiteral(Literal literal) {
    return switch (literal.type()) {
      case STRING -> "runtime.text(" + javaString(literal.value()) + ")";
      case NUMBER -> "runtime.number(" + javaString(literal.value()) + ")";
      case BOOLEAN -> "runtime.bool(" + literal.value() + ")";
      case NULL -> "runtime.nullValue()";
    };
  }

  private void emitLocalDeclarations(
      StringBuilder source,
      Map<Expression, String> commonExpressions,
      Map<List<PathStep>, Integer> paths,
      Map<Literal, String> literals) {
    commonExpressions.forEach(
        (expression, name) -> {
          Map<Expression, String> dependencies = new LinkedHashMap<>(commonExpressions);
          dependencies.remove(expression);
          source
              .append("    JsonValue ")
              .append(name)
              .append(" = ")
              .append(emitExpression(expression, paths, literals, dependencies))
              .append(";\n");
        });
  }

  private static Map<Expression, String> collectCommonExpressions(List<Expression> roots) {
    Map<Expression, Integer> counts = new LinkedHashMap<>();
    roots.forEach(root -> countCommonExpressions(root, counts));
    Map<Expression, String> locals = new LinkedHashMap<>();
    roots.forEach(root -> selectCommonExpressions(root, counts, locals));
    return locals;
  }

  private static void countCommonExpressions(
      Expression expression, Map<Expression, Integer> counts) {
    switch (expression) {
      case Literal ignored -> {}
      case Field ignored -> {}
      case Unary unary -> countCommonExpressions(unary.operand(), counts);
      case Binary binary -> {
        countCommonExpressions(binary.left(), counts);
        countCommonExpressions(binary.right(), counts);
      }
      case FunctionCall function ->
          function.arguments().forEach(argument -> countCommonExpressions(argument, counts));
    }
    if (isCommonExpressionCandidate(expression)) {
      counts.merge(expression, 1, Integer::sum);
    }
  }

  private static void selectCommonExpressions(
      Expression expression, Map<Expression, Integer> counts, Map<Expression, String> locals) {
    switch (expression) {
      case Literal ignored -> {}
      case Field ignored -> {}
      case Unary unary -> selectCommonExpressions(unary.operand(), counts, locals);
      case Binary binary -> {
        selectCommonExpressions(binary.left(), counts, locals);
        selectCommonExpressions(binary.right(), counts, locals);
      }
      case FunctionCall function ->
          function
              .arguments()
              .forEach(argument -> selectCommonExpressions(argument, counts, locals));
    }
    if (counts.getOrDefault(expression, 0) > 1) {
      locals.computeIfAbsent(expression, ignored -> "common" + locals.size());
    }
  }

  private static boolean isCommonExpressionCandidate(Expression expression) {
    return switch (expression) {
      case Literal ignored -> false;
      case Field ignored -> false;
      case Unary unary -> isPureExpression(unary.operand());
      case Binary binary -> isPureExpression(binary.left()) && isPureExpression(binary.right());
      case FunctionCall ignored -> false;
    };
  }

  private static boolean isPureExpression(Expression expression) {
    return switch (expression) {
      case Literal ignored -> true;
      case Field ignored -> true;
      case Unary unary -> isPureExpression(unary.operand());
      case Binary binary -> isPureExpression(binary.left()) && isPureExpression(binary.right());
      case FunctionCall ignored -> false;
    };
  }

  private void validateFunctions(Query query) {
    query.projections().forEach(projection -> validateFunctions(projection.expression()));
    if (query.predicate() != null) {
      validateFunctions(query.predicate());
    }
  }

  private void validateFunctions(Expression expression) {
    switch (expression) {
      case Literal ignored -> {}
      case Field ignored -> {}
      case Unary unary -> validateFunctions(unary.operand());
      case Binary binary -> {
        validateFunctions(binary.left());
        validateFunctions(binary.right());
      }
      case FunctionCall function -> {
        runtime.validateFunction(function.name(), function.arguments().size());
        function.arguments().forEach(this::validateFunctions);
      }
    }
  }

  private static Map<List<PathStep>, Integer> collectPaths(Query query) {
    Map<List<PathStep>, Integer> paths = new LinkedHashMap<>();
    query.projections().forEach(projection -> collectPaths(projection.expression(), paths));
    if (query.predicate() != null) {
      collectPaths(query.predicate(), paths);
    }
    return paths;
  }

  private static void collectPaths(Expression expression, Map<List<PathStep>, Integer> paths) {
    switch (expression) {
      case Literal ignored -> {}
      case Field field -> paths.computeIfAbsent(field.path(), ignored -> paths.size());
      case Unary unary -> collectPaths(unary.operand(), paths);
      case Binary binary -> {
        collectPaths(binary.left(), paths);
        collectPaths(binary.right(), paths);
      }
      case FunctionCall function ->
          function.arguments().forEach(argument -> collectPaths(argument, paths));
    }
  }

  private static Map<Literal, String> collectLiterals(Query query) {
    Map<Literal, String> literals = new LinkedHashMap<>();
    query.projections().forEach(projection -> collectLiterals(projection.expression(), literals));
    if (query.predicate() != null) {
      collectLiterals(query.predicate(), literals);
    }
    return literals;
  }

  private static void collectLiterals(Expression expression, Map<Literal, String> literals) {
    switch (expression) {
      case Literal literal ->
          literals.computeIfAbsent(literal, ignored -> "literal" + literals.size());
      case Field ignored -> {}
      case Unary unary -> collectLiterals(unary.operand(), literals);
      case Binary binary -> {
        collectLiterals(binary.left(), literals);
        collectLiterals(binary.right(), literals);
      }
      case FunctionCall function ->
          function.arguments().forEach(argument -> collectLiterals(argument, literals));
    }
  }

  private static String hash(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 10);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static String javaString(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 16).append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '\\' -> escaped.append("\\\\");
        case '"' -> escaped.append("\\\"");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        default -> {
          if (character < 0x20 || character == 0x2028 || character == 0x2029) {
            escaped.append(String.format("\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.append('"').toString();
  }

  public record GeneratedSource(
      String className, String source, List<List<PathStep>> selectedPaths) {}
}
