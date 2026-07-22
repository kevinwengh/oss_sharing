/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream;

import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.kevinwen.jsonstream.internal.CanonicalQueryFormatter;
import com.kevinwen.jsonstream.internal.InMemoryJavaCompiler;
import com.kevinwen.jsonstream.internal.JavaSourceGenerator;
import com.kevinwen.jsonstream.internal.JavaSourceGenerator.GeneratedSource;
import com.kevinwen.jsonstream.internal.QueryModel;
import com.kevinwen.jsonstream.internal.QueryModel.Query;
import com.kevinwen.jsonstream.internal.QueryParser;
import com.kevinwen.jsonstream.internal.SelectiveJsonReader;

/** Parses, generates, compiles, and caches raw-string JSON transformations. */
public final class JsonTransformEngine {
  private final JsonExpressionRuntime runtime;
  private final JavaSourceGenerator sourceGenerator;
  private final InMemoryJavaCompiler compiler;
  private final ConcurrentHashMap<String, CompletableFuture<CompiledTransform>> exactCache =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, CompletableFuture<CompiledTransform>>
      canonicalCache = new ConcurrentHashMap<>();
  private final LongAdder hits = new LongAdder();
  private final LongAdder misses = new LongAdder();
  private final LongAdder generations = new LongAdder();
  private final LongAdder compilations = new LongAdder();

  public JsonTransformEngine() {
    this(JsonExpressionRuntime.standard());
  }

  public JsonTransformEngine(JsonExpressionRuntime runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    sourceGenerator = new JavaSourceGenerator(runtime);
    compiler = new InMemoryJavaCompiler(JsonTransformEngine.class.getClassLoader());
  }

  public CompiledTransform compile(String queryText) {
    Objects.requireNonNull(queryText, "queryText");
    CompletableFuture<CompiledTransform> existing = exactCache.get(queryText);
    if (existing != null) {
      hits.increment();
      return await(existing);
    }

    CompletableFuture<CompiledTransform> candidate = new CompletableFuture<>();
    existing = exactCache.putIfAbsent(queryText, candidate);
    if (existing != null) {
      hits.increment();
      return await(existing);
    }

    String canonical = null;
    try {
      Query query = new QueryParser(queryText).parse();
      canonical = new CanonicalQueryFormatter().format(query);
      existing = canonicalCache.putIfAbsent(canonical, candidate);
      if (existing != null) {
        hits.increment();
        CompiledTransform result = await(existing);
        candidate.complete(result);
        return result;
      }

      misses.increment();
      generations.increment();
      GeneratedSource generated = sourceGenerator.generate(query, canonical);
      compilations.increment();
      Class<?> generatedClass = compiler.compile(generated.className(), generated.source());
      if (!JsonTransformer.class.isAssignableFrom(generatedClass)) {
        throw new QueryCompilationException(
            "Generated class does not implement JsonTransformer", generated.source());
      }
      SelectiveJsonReader reader = new SelectiveJsonReader(generated.selectedPaths());
      JsonTransformer transformer = instantiate(generatedClass, reader, generated.source());
      CompiledTransform result =
          new CompiledTransform(
              canonical,
              generated.className(),
              generated.source(),
              generated.selectedPaths().stream().map(QueryModel::renderPath).toList(),
              transformer);
      candidate.complete(result);
      return result;
    } catch (Throwable failure) {
      candidate.completeExceptionally(failure);
      exactCache.remove(queryText, candidate);
      if (canonical != null) {
        canonicalCache.remove(canonical, candidate);
      }
      throw propagate(failure);
    }
  }

  public String transform(String queryText, String input) {
    return compile(queryText).transform(input);
  }

  public CacheStats cacheStats() {
    return new CacheStats(
        hits.sum(), misses.sum(), generations.sum(), compilations.sum(), canonicalCache.size());
  }

  private JsonTransformer instantiate(
      Class<?> generatedClass, SelectiveJsonReader reader, String source) {
    try {
      return (JsonTransformer)
          generatedClass
              .getConstructor(JsonExpressionRuntime.class, SelectiveJsonReader.class)
              .newInstance(runtime, reader);
    } catch (NoSuchMethodException
        | InstantiationException
        | IllegalAccessException
        | InvocationTargetException e) {
      throw new QueryCompilationException(
          "Could not instantiate generated class " + generatedClass.getName(), source, e);
    }
  }

  private static CompiledTransform await(CompletableFuture<CompiledTransform> future) {
    try {
      return future.join();
    } catch (CompletionException e) {
      throw propagate(e.getCause());
    }
  }

  private static RuntimeException propagate(Throwable failure) {
    if (failure instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    if (failure instanceof Error error) {
      throw error;
    }
    return new QueryException("Unexpected transformation compilation failure", failure);
  }
}
