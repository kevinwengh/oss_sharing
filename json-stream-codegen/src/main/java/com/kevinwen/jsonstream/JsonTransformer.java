/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream;

/** A compiled, thread-safe transformation from one raw JSON object string to another. */
@FunctionalInterface
public interface JsonTransformer {
  /** Returns a JSON object string, or {@code null} when the query predicate does not match. */
  String transform(String input);
}
