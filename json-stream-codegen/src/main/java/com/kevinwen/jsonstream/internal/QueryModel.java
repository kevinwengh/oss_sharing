/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream.internal;

import java.util.List;
import java.util.Objects;

/** Immutable syntax tree for the SQL-like expression language. */
public final class QueryModel {
  private QueryModel() {}

  /** A single navigation step in a field path: an object key or an array index. */
  public sealed interface PathStep permits Key, Index {}

  /** Navigation into an object member by name (the {@code .field} form). */
  public record Key(String name) implements PathStep {
    public Key {
      Objects.requireNonNull(name, "name");
    }
  }

  /** Navigation into an array element by zero-based position (the {@code [n]} form). */
  public record Index(int index) implements PathStep {
    public Index {
      if (index < 0) {
        throw new IllegalArgumentException("Array index must be non-negative: " + index);
      }
    }
  }

  /** Renders a path as a human-readable dotted/bracketed string, e.g. {@code items[0].price}. */
  public static String renderPath(List<PathStep> path) {
    StringBuilder rendered = new StringBuilder();
    for (PathStep step : path) {
      switch (step) {
        case Key key -> {
          if (!rendered.isEmpty()) {
            rendered.append('.');
          }
          rendered.append(key.name());
        }
        case Index index -> rendered.append('[').append(index.index()).append(']');
      }
    }
    return rendered.toString();
  }

  public record Query(List<Projection> projections, Expression predicate) {
    public Query {
      projections = List.copyOf(projections);
    }
  }

  public record Projection(Expression expression, String alias) {}

  public sealed interface Expression permits Literal, Field, Unary, Binary, FunctionCall {}

  public enum LiteralType {
    STRING,
    NUMBER,
    BOOLEAN,
    NULL
  }

  public record Literal(LiteralType type, String value) implements Expression {}

  public record Field(List<PathStep> path) implements Expression {
    public Field {
      path = List.copyOf(path);
    }
  }

  public record Unary(String operator, Expression operand) implements Expression {}

  public record Binary(String operator, Expression left, Expression right) implements Expression {}

  public record FunctionCall(String name, List<Expression> arguments) implements Expression {
    public FunctionCall {
      arguments = List.copyOf(arguments);
    }
  }
}
