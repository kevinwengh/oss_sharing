/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream;

/** Indicates invalid transformation query text. */
public final class QueryParseException extends QueryException {
  public QueryParseException(String message) {
    super(message);
  }
}
