/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream;

import java.util.List;
import java.util.Objects;

/**
 * A generated source artifact and its loaded raw-string transformer.
 *
 * <p>{@code selectedPaths} lists the input paths materialized by the streaming reader, rendered in
 * the query's own dotted/bracketed syntax, for example {@code items[0].price}.
 */
public record CompiledTransform(
    String canonicalQuery,
    String generatedClassName,
    String generatedSource,
    List<String> selectedPaths,
    JsonTransformer transformer) {

  public CompiledTransform {
    Objects.requireNonNull(canonicalQuery, "canonicalQuery");
    Objects.requireNonNull(generatedClassName, "generatedClassName");
    Objects.requireNonNull(generatedSource, "generatedSource");
    selectedPaths = List.copyOf(selectedPaths);
    Objects.requireNonNull(transformer, "transformer");
  }

  public String transform(String input) {
    return transformer.transform(Objects.requireNonNull(input, "input"));
  }
}
