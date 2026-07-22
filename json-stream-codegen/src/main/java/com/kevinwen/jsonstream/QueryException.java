/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream;

/** Base exception for query parsing, compilation, and evaluation failures. */
public class QueryException extends RuntimeException {
  public QueryException(String message) {
    super(message);
  }

  public QueryException(String message, Throwable cause) {
    super(message, cause);
  }
}
