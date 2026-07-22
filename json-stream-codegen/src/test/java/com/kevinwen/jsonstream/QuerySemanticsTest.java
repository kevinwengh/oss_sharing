/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins the observable semantics of every supported query shape: path selection, arithmetic,
 * standard functions, null handling, comparisons, and predicate rejection.
 */
class QuerySemanticsTest {
  @Test
  void producesExpectedOutputForSupportedQueryShapes() {
    List<Case> cases =
        List.of(
            new Case(
                "SELECT customer.name AS name, active",
                "{\"customer\":{\"name\":\"Ada\"},\"active\":true}",
                "{\"name\":\"Ada\",\"active\":true}"),
            new Case(
                "SELECT amount + 2 AS plus, amount - 2 AS minus, amount * 2 AS times, "
                    + "amount / 2 AS divided, amount % 2 AS remainder",
                "{\"amount\":5}",
                "{\"plus\":7,\"minus\":3,\"times\":10,\"divided\":2.5,\"remainder\":1}"),
            new Case(
                "SELECT UPPER(name) AS upper, LOWER(name) AS lower, "
                    + "CONCAT(name, '-', suffix) AS joined, LENGTH(tags) AS size",
                "{\"name\":\"Ada\",\"suffix\":\"X\",\"tags\":[1,2,3]}",
                "{\"upper\":\"ADA\",\"lower\":\"ada\",\"joined\":\"Ada-X\",\"size\":3}"),
            new Case(
                "SELECT COALESCE(missing, fallback) AS value, missing AS absent",
                "{\"fallback\":\"known\"}",
                "{\"value\":\"known\",\"absent\":null}"),
            new Case(
                "SELECT profile AS profile, ROUND(amount * 1.125, 2) AS rounded "
                    + "WHERE active = true AND amount >= 10",
                "{\"profile\":{\"roles\":[\"admin\"]},\"amount\":12.5,\"active\":true}",
                "{\"profile\":{\"roles\":[\"admin\"]},\"rounded\":14.06}"),
            new Case(
                "SELECT amount = 10 AS equal, amount != 11 AS different, "
                    + "amount < 20 AS less, amount >= 10 AS enough",
                "{\"amount\":10}",
                "{\"equal\":true,\"different\":true,\"less\":true,\"enough\":true}"));

    JsonTransformEngine engine = new JsonTransformEngine();
    for (Case testCase : cases) {
      assertEquals(
          testCase.expected(), engine.transform(testCase.query(), testCase.json()), testCase.query());
    }
  }

  @Test
  void rejectsInputWhosePredicateIsFalse() {
    assertNull(
        new JsonTransformEngine()
            .transform("SELECT name WHERE active = true", "{\"name\":\"Ada\",\"active\":false}"));
  }

  private record Case(String query, String json, String expected) {}
}
