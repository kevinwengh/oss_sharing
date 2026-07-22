/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream;

import java.util.List;

/** A caller-supplied function. Implementations must be thread-safe. */
@FunctionalInterface
public interface JsonFunction {
  JsonValue apply(List<JsonValue> arguments);
}
