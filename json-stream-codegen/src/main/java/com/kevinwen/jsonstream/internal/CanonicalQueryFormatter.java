/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream.internal;

import java.util.stream.Collectors;

import com.kevinwen.jsonstream.internal.QueryModel.Binary;
import com.kevinwen.jsonstream.internal.QueryModel.Expression;
import com.kevinwen.jsonstream.internal.QueryModel.Field;
import com.kevinwen.jsonstream.internal.QueryModel.FunctionCall;
import com.kevinwen.jsonstream.internal.QueryModel.Index;
import com.kevinwen.jsonstream.internal.QueryModel.Key;
import com.kevinwen.jsonstream.internal.QueryModel.Literal;
import com.kevinwen.jsonstream.internal.QueryModel.PathStep;
import com.kevinwen.jsonstream.internal.QueryModel.Projection;
import com.kevinwen.jsonstream.internal.QueryModel.Query;
import com.kevinwen.jsonstream.internal.QueryModel.Unary;

/** Emits an unambiguous cache key for a parsed query. */
public final class CanonicalQueryFormatter {
  public String format(Query query) {
    String projections =
        query.projections().stream().map(this::formatProjection).collect(Collectors.joining(","));
    return "SELECT["
        + projections
        + "]WHERE["
        + (query.predicate() == null ? "" : formatExpression(query.predicate()))
        + "]";
  }

  private String formatProjection(Projection projection) {
    return lengthPrefix(projection.alias()) + "=" + formatExpression(projection.expression());
  }

  private String formatExpression(Expression expression) {
    return switch (expression) {
      case Literal literal -> "L" + literal.type() + lengthPrefix(literal.value());
      case Field field ->
          "F"
              + field.path().stream()
                  .map(CanonicalQueryFormatter::formatStep)
                  .collect(Collectors.joining());
      case Unary unary -> "U" + lengthPrefix(unary.operator()) + formatExpression(unary.operand());
      case Binary binary ->
          "B"
              + lengthPrefix(binary.operator())
              + "("
              + formatExpression(binary.left())
              + ","
              + formatExpression(binary.right())
              + ")";
      case FunctionCall function ->
          "C"
              + lengthPrefix(function.name())
              + "("
              + function.arguments().stream()
                  .map(this::formatExpression)
                  .collect(Collectors.joining(","))
              + ")";
    };
  }

  private static String formatStep(PathStep step) {
    return switch (step) {
      case Key key -> "K" + lengthPrefix(key.name());
      case Index index -> "I" + index.index() + ";";
    };
  }

  private static String lengthPrefix(String value) {
    return value.length() + ":" + value;
  }
}
