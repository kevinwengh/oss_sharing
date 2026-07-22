/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream;

/** Indicates failure while compiling generated Java source. */
public final class QueryCompilationException extends QueryException {
  private final String generatedSource;

  public QueryCompilationException(String message, String generatedSource) {
    super(message);
    this.generatedSource = generatedSource;
  }

  public QueryCompilationException(String message, String generatedSource, Throwable cause) {
    super(message, cause);
    this.generatedSource = generatedSource;
  }

  public String generatedSource() {
    return generatedSource;
  }
}
