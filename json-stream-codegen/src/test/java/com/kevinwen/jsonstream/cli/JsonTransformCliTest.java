/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class JsonTransformCliTest {
  @Test
  void transformsJsonReadFromStandardInput() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream error = new ByteArrayOutputStream();

    int exitCode =
        JsonTransformCli.run(
            new String[] {"--query", "SELECT UPPER(name) AS name"},
            new ByteArrayInputStream("{\"name\":\"Ada\"}".getBytes(StandardCharsets.UTF_8)),
            new PrintStream(output),
            new PrintStream(error));

    assertEquals(0, exitCode);
    assertEquals("{\"name\":\"ADA\"}" + System.lineSeparator(), output.toString());
    assertEquals("", error.toString());
  }

  @Test
  void reportsMalformedJson() {
    ByteArrayOutputStream error = new ByteArrayOutputStream();

    int exitCode =
        JsonTransformCli.run(
            new String[] {"--query", "SELECT name", "--json", "{"},
            InputStreamStub.EMPTY,
            new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(error));

    assertEquals(2, exitCode);
    assertTrue(error.toString().contains("Invalid input JSON"));
  }

  private static final class InputStreamStub {
    private static final ByteArrayInputStream EMPTY = new ByteArrayInputStream(new byte[0]);
  }
}
