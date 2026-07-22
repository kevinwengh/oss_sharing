/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream.benchmark;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kevinwen.jsonstream.CompiledTransform;
import com.kevinwen.jsonstream.JsonTransformEngine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * End-to-end comparison against full Jackson tree materialization and serialization.
 *
 * <p>The baseline hand-writes the same projection over a materialized {@code ObjectNode}, so the
 * measured difference is the cost of building and discarding the input tree rather than a
 * difference in expression evaluation strategy.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class JsonTransformBenchmark {
  private static final String QUERY =
      "SELECT customer.name AS customerName, UPPER(city) AS city, "
          + "ROUND(amount * 1.1, 2) AS adjustedAmount WHERE active = true";

  private final ObjectMapper mapper = new ObjectMapper();
  private CompiledTransform streaming;
  private String smallJson;
  private String largeSparseJson;
  private ObjectNode parsedSmall;

  @Setup
  public void setUp() throws Exception {
    streaming = new JsonTransformEngine().compile(QUERY);
    smallJson =
        "{\"customer\":{\"name\":\"Ada\"},\"city\":\"london\","
            + "\"amount\":12.5,\"active\":true}";
    StringBuilder large = new StringBuilder(64_000).append('{');
    for (int field = 0; field < 200; field++) {
      if (field != 0) {
        large.append(',');
      }
      large.append("\"unused").append(field).append("\":[");
      for (int value = 0; value < 20; value++) {
        if (value != 0) {
          large.append(',');
        }
        large.append(value);
      }
      large.append(']');
    }
    large.append(
        ",\"customer\":{\"name\":\"Ada\"},\"city\":\"london\","
            + "\"amount\":12.5,\"active\":true}");
    largeSparseJson = large.toString();
    parsedSmall = (ObjectNode) mapper.readTree(smallJson);
  }

  @Benchmark
  public String streamingSmallEndToEnd() {
    return streaming.transform(smallJson);
  }

  @Benchmark
  public String treeSmallEndToEnd() throws Exception {
    ObjectNode input = (ObjectNode) mapper.readTree(smallJson);
    return mapper.writeValueAsString(treeTransform(input));
  }

  @Benchmark
  public String streamingLargeSparseEndToEnd() {
    return streaming.transform(largeSparseJson);
  }

  @Benchmark
  public String treeLargeSparseEndToEnd() throws Exception {
    ObjectNode input = (ObjectNode) mapper.readTree(largeSparseJson);
    return mapper.writeValueAsString(treeTransform(input));
  }

  /** Retained-tree hot path: the input tree already exists and only the projection is measured. */
  @Benchmark
  public ObjectNode existingTreeHotPath() {
    return treeTransform(parsedSmall);
  }

  private ObjectNode treeTransform(ObjectNode input) {
    if (!input.path("active").asBoolean(false)) {
      return null;
    }
    ObjectNode output = mapper.createObjectNode();
    output.put("customerName", input.path("customer").path("name").asText(null));
    String city = input.path("city").asText(null);
    output.put("city", city == null ? null : city.toUpperCase(Locale.ROOT));
    BigDecimal amount = input.path("amount").decimalValue();
    output.put(
        "adjustedAmount",
        amount
            .multiply(new BigDecimal("1.1"), MathContext.DECIMAL128)
            .setScale(2, RoundingMode.HALF_UP));
    return output;
  }
}
