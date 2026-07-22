/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

class JsonTransformEngineTest {
  @Test
  void transformsRawJsonWithoutADataBindInputModel() {
    CompiledTransform compiled =
        new JsonTransformEngine()
            .compile(
                "SELECT customer.name AS customerName, UPPER(city) AS city, "
                    + "ROUND(amount * 1.1, 2) AS adjusted WHERE active = true");

    assertEquals(
        "{\"customerName\":\"Ada\",\"city\":\"LONDON\",\"adjusted\":13.75}",
        compiled.transform(
            "{\"ignored\":{\"large\":[1,2,3]},\"active\":true,\"amount\":12.5,"
                + "\"city\":\"london\",\"customer\":{\"name\":\"Ada\"}}"));
    assertEquals(List.of("customer.name", "city", "amount", "active"), compiled.selectedPaths());
  }

  @Test
  void predicateFalseAndMissingPredicateReturnNoOutput() {
    CompiledTransform compiled =
        new JsonTransformEngine().compile("SELECT name WHERE active = true");

    assertNull(compiled.transform("{\"name\":\"Ada\",\"active\":false}"));
    assertNull(compiled.transform("{\"name\":\"Ada\"}"));
  }

  @Test
  void preservesNullAndTypeSemantics() {
    JsonTransformEngine engine = new JsonTransformEngine();

    assertEquals(
        "{\"missing\":null,\"sum\":null,\"fallback\":\"unknown\"}",
        engine.transform(
            "SELECT missing, missing + 1 AS sum, COALESCE(missing, 'unknown') AS fallback", "{}"));
    assertThrows(
        QueryEvaluationException.class,
        () -> engine.transform("SELECT amount + 1 AS result", "{\"amount\":\"ten\"}"));
    assertThrows(
        QueryEvaluationException.class,
        () -> engine.transform("SELECT amount / 0 AS result", "{\"amount\":2}"));
  }

  @Test
  void projectsOnlyRequestedContainerAndEscapesOutput() {
    String result =
        new JsonTransformEngine()
            .transform(
                "SELECT profile, note",
                "{\"profile\":{\"name\":\"A\\\"B\",\"tags\":[\"x\",null,true]},"
                    + "\"note\":\"line\\nfeed\",\"discard\":[1,2,3]}");

    assertEquals(
        "{\"profile\":{\"name\":\"A\\\"B\",\"tags\":[\"x\",null,true]},"
            + "\"note\":\"line\\nfeed\"}",
        result);
  }

  @Test
  void supportsParentAndDescendantSelectionsTogether() {
    String result =
        new JsonTransformEngine()
            .transform(
                "SELECT profile, profile.name AS name, profile.address.city AS city",
                "{\"profile\":{\"name\":\"Ada\",\"address\":{\"city\":\"London\"}}}");

    assertEquals(
        "{\"profile\":{\"name\":\"Ada\",\"address\":{\"city\":\"London\"}},"
            + "\"name\":\"Ada\",\"city\":\"London\"}",
        result);
  }

  @Test
  void indexesIntoArraysIncludingNestedObjectsAndArrays() {
    JsonTransformEngine engine = new JsonTransformEngine();

    CompiledTransform compiled =
        engine.compile(
            "SELECT items[0] AS first, items[1].price AS second, "
                + "matrix[1][0] AS cell, UPPER(tags[2]) AS third");

    assertEquals(
        "{\"first\":{\"price\":10},\"second\":20,\"cell\":3,\"third\":\"C\"}",
        compiled.transform(
            "{\"items\":[{\"price\":10},{\"price\":20}],"
                + "\"matrix\":[[1,2],[3,4]],\"tags\":[\"a\",\"b\",\"c\"]}"));
    assertEquals(
        List.of("items[0]", "items[1].price", "matrix[1][0]", "tags[2]"), compiled.selectedPaths());
  }

  @Test
  void arrayIndexOutOfRangeAndTypeMismatchYieldJsonNull() {
    JsonTransformEngine engine = new JsonTransformEngine();

    assertEquals(
        "{\"missing\":null,\"wrongType\":null}",
        engine.transform(
            "SELECT items[5] AS missing, items[0].price AS wrongType", "{\"items\":[1,2,3]}"));
    assertEquals(
        "{\"missing\":null}",
        engine.transform("SELECT items[0] AS missing", "{\"items\":\"notAnArray\"}"));
  }

  @Test
  void materializedArrayContainerAndItsIndexedDescendantShareOneParse() {
    String result =
        new JsonTransformEngine()
            .transform(
                "SELECT items, items[0].price AS lead, items[1] AS second",
                "{\"items\":[{\"price\":10},{\"price\":20}]}");

    assertEquals(
        "{\"items\":[{\"price\":10},{\"price\":20}],\"lead\":10,\"second\":{\"price\":20}}",
        result);
  }

  @Test
  void bareArrayIndexProjectionRequiresAnAlias() {
    JsonTransformEngine engine = new JsonTransformEngine();

    QueryParseException failure =
        assertThrows(QueryParseException.class, () -> engine.compile("SELECT items[0]"));
    assertTrue(failure.getMessage().contains("require AS"));
    assertThrows(QueryParseException.class, () -> engine.compile("SELECT items[1.5] AS x"));
    assertThrows(QueryParseException.class, () -> engine.compile("SELECT items[0 AS x"));
  }

  @Test
  void arrayAndObjectPathsToTheSameNameAreDistinctCacheEntries() {
    JsonTransformEngine engine = new JsonTransformEngine();

    CompiledTransform arrayPath = engine.compile("SELECT a[0] AS v");
    CompiledTransform objectPath = engine.compile("SELECT a.b AS v");

    assertEquals("{\"v\":1}", arrayPath.transform("{\"a\":[1,2]}"));
    assertEquals("{\"v\":1}", objectPath.transform("{\"a\":{\"b\":1}}"));
    assertTrue(arrayPath != objectPath);
  }

  @Test
  void duplicateFieldsUseLastValueIncludingParentReplacement() {
    JsonTransformEngine engine = new JsonTransformEngine();

    assertEquals(
        "{\"name\":\"Grace\"}",
        engine.transform(
            "SELECT profile.name AS name",
            "{\"profile\":{\"name\":\"Ada\"}," + "\"profile\":{\"name\":\"Grace\"}}"));
    assertEquals(
        "{\"name\":null}",
        engine.transform(
            "SELECT profile.name AS name",
            "{\"profile\":{\"name\":\"Ada\"}," + "\"profile\":null}"));
  }

  @Test
  void rejectsMalformedNonObjectAndTrailingInputEvenInUnselectedFields() {
    CompiledTransform compiled = new JsonTransformEngine().compile("SELECT name");

    assertThrows(QueryEvaluationException.class, () -> compiled.transform("[]"));
    assertThrows(QueryEvaluationException.class, () -> compiled.transform("{\"name\":1} {}"));
    assertThrows(
        QueryEvaluationException.class, () -> compiled.transform("{\"name\":1,\"ignored\":[1,}"));
  }

  @Test
  void supportsAllOperatorsAndStandardFunctions() {
    String result =
        new JsonTransformEngine()
            .transform(
                "SELECT -amount AS negative, amount + 3 AS plus, amount - 1 AS minus, "
                    + "amount * 2 AS times, amount / 2 AS divided, amount % 3 AS remainder, "
                    + "ABS(-amount) AS absolute, LENGTH(items) AS itemCount, "
                    + "LOWER(word) AS lower, CONCAT(word, '-', suffix) AS joined "
                    + "WHERE amount >= 5 AND amount < 10 AND NOT disabled",
                "{\"amount\":5,\"items\":[1,2],\"word\":\"HELLO\","
                    + "\"suffix\":\"X\",\"disabled\":false}");

    assertEquals(
        "{\"negative\":-5,\"plus\":8,\"minus\":4,\"times\":10,\"divided\":2.5,"
            + "\"remainder\":2,\"absolute\":5,\"itemCount\":2,\"lower\":\"hello\","
            + "\"joined\":\"HELLO-X\"}",
        result);
  }

  @Test
  void supportsCustomFunctionsWithoutJacksonNodes() {
    JsonExpressionRuntime runtime =
        JsonExpressionRuntime.builder()
            .withStandardFunctions()
            .function(
                "REVERSE",
                1,
                arguments -> {
                  JsonValue.StringValue input =
                      assertInstanceOf(JsonValue.StringValue.class, arguments.getFirst());
                  return JsonValue.text(new StringBuilder(input.value()).reverse().toString());
                })
            .build();

    assertEquals(
        "{\"reversed\":\"adA\"}",
        new JsonTransformEngine(runtime)
            .transform("SELECT REVERSE(name) AS reversed", "{\"name\":\"Ada\"}"));
  }

  @Test
  void canonicalCacheDeduplicatesEquivalentQueries() {
    JsonTransformEngine engine = new JsonTransformEngine();

    CompiledTransform first = engine.compile("SELECT name FROM input;");
    CompiledTransform second = engine.compile(" select name ");
    CompiledTransform third = engine.compile("SELECT name FROM input;");

    assertSame(first, second);
    assertSame(first, third);
    assertEquals(new CacheStats(2, 1, 1, 1, 1), engine.cacheStats());
  }

  @Test
  void compilationAndTransformationAreThreadSafe() throws Exception {
    JsonTransformEngine engine = new JsonTransformEngine();
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Callable<CompiledTransform>> compilationTasks = new ArrayList<>();
      for (int index = 0; index < 32; index++) {
        compilationTasks.add(() -> engine.compile("SELECT amount * 2 AS doubled"));
      }
      List<CompiledTransform> transforms =
          executor.invokeAll(compilationTasks).stream()
              .map(
                  future -> {
                    try {
                      return future.get();
                    } catch (Exception e) {
                      throw new AssertionError(e);
                    }
                  })
              .toList();
      transforms.forEach(transform -> assertSame(transforms.getFirst(), transform));

      List<Callable<String>> transformationTasks = new ArrayList<>();
      for (int value = 0; value < 64; value++) {
        int input = value;
        transformationTasks.add(
            () -> transforms.getFirst().transform("{\"amount\":" + input + "}"));
      }
      List<String> outputs =
          executor.invokeAll(transformationTasks).stream()
              .map(
                  future -> {
                    try {
                      return future.get();
                    } catch (Exception e) {
                      throw new AssertionError(e);
                    }
                  })
              .toList();
      for (int value = 0; value < outputs.size(); value++) {
        assertEquals("{\"doubled\":" + (value * 2) + "}", outputs.get(value));
      }
    }
    assertEquals(1, engine.cacheStats().compilations());
  }

  @Test
  void queryValidationProvidesActionableFailures() {
    JsonTransformEngine engine = new JsonTransformEngine();

    assertThrows(QueryParseException.class, () -> engine.compile("SELECT amount + 1"));
    assertThrows(QueryParseException.class, () -> engine.compile("SELECT missing(name) AS x"));
    assertThrows(
        QueryParseException.class, () -> engine.compile("SELECT name, profile.name AS name"));
    QueryParseException failure =
        assertThrows(QueryParseException.class, () -> engine.compile("SELECT 'unterminated AS x"));
    assertTrue(failure.getMessage().contains("Unterminated string"));
  }

  @Test
  void generatedSourceContainsDirectSlotAccessAndNoDataBindTypes() {
    CompiledTransform compiled =
        new JsonTransformEngine().compile("SELECT profile.name AS name");

    assertTrue(compiled.generatedSource().contains("JsonValue[] fields = reader.read(input)"));
    assertTrue(
        compiled.generatedSource().contains("runtime.writeObject1(OUTPUT_PREFIXES, fields[0])"));
    assertTrue(!compiled.generatedSource().contains("JsonNode"));
    assertNotNull(compiled.generatedClassName());
  }

  @Test
  void generatedSourceEliminatesRepeatedPureExpressions() {
    CompiledTransform compiled =
        new JsonTransformEngine()
            .compile(
                "SELECT amount * 1.1 AS gross, amount * 1.1 AS billed, "
                    + "customer.name AS customer, customer.name AS display");

    assertTrue(
        compiled
            .generatedSource()
            .contains("JsonValue common0 = runtime.multiply(fields[0], literal0);"));
    assertTrue(compiled.generatedSource().contains("runtime.writeObject(OUTPUT_PREFIXES, output)"));
    assertEquals(
        "{\"gross\":11.0,\"billed\":11.0,\"customer\":\"Ada\",\"display\":\"Ada\"}",
        compiled.transform("{\"amount\":10,\"customer\":{\"name\":\"Ada\"}}"));
  }

  @Test
  void rejectsPathologicalFlatExpressionsBeforeSourceGeneration() {
    StringBuilder query = new StringBuilder("SELECT 1");
    for (int index = 0; index < 70; index++) {
      query.append(" + 1");
    }
    query.append(" AS value");

    QueryParseException failure =
        assertThrows(
            QueryParseException.class,
            () -> new JsonTransformEngine().compile(query.toString()));
    assertTrue(failure.getMessage().contains("nesting exceeds"));
  }

  @Test
  void publicValueFactoriesCannotProduceInvalidJsonNumbers() {
    assertThrows(NumberFormatException.class, () -> JsonValue.number("+1"));
    assertThrows(NumberFormatException.class, () -> JsonValue.number("01"));
    assertThrows(NumberFormatException.class, () -> JsonValue.number("1."));

    StringBuilder output = new StringBuilder();
    JsonValue.number("-1.25e+2").appendJson(output);
    assertEquals("-1.25e+2", output.toString());
  }

  @Test
  void serializerEscapesUnpairedSurrogates() {
    assertEquals(
        "{\"value\":\"\\ud800\"}",
        new JsonTransformEngine().transform("SELECT value", "{\"value\":\"\\ud800\"}"));
  }
}
