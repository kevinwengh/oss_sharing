/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream;

/** Indicates malformed input JSON or an expression evaluation failure. */
public final class QueryEvaluationException extends QueryException {
  public QueryEvaluationException(String message) {
    super(message);
  }

  public QueryEvaluationException(String message, Throwable cause) {
    super(message, cause);
  }
}
