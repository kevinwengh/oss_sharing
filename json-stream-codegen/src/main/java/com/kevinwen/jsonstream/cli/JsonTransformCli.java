/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.kevinwen.jsonstream.CompiledTransform;
import com.kevinwen.jsonstream.JsonTransformEngine;
import com.kevinwen.jsonstream.QueryException;

/** Command-line entry point that never creates a Jackson tree model. */
public final class JsonTransformCli {
  private JsonTransformCli() {}

  public static void main(String[] args) {
    int exitCode = run(args, System.in, System.out, System.err);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  public static int run(
      String[] args, InputStream standardInput, PrintStream output, PrintStream error) {
    try {
      Map<String, String> options = parseOptions(args);
      if (options.containsKey("help")) {
        printUsage(output);
        return 0;
      }
      String query = require(options, "query");
      String json =
          options.containsKey("json")
              ? options.get("json")
              : new String(standardInput.readAllBytes(), StandardCharsets.UTF_8);
      CompiledTransform compiled = new JsonTransformEngine().compile(query);
      if (options.containsKey("show-source")) {
        error.println("--- " + compiled.generatedClassName() + " ---");
        error.print(compiled.generatedSource());
      }
      String result = compiled.transform(json);
      output.println(result == null ? "null" : result);
      return 0;
    } catch (IllegalArgumentException | QueryException | IOException e) {
      error.println("Error: " + e.getMessage());
      error.println("Use --help for usage.");
      return 2;
    }
  }

  private static Map<String, String> parseOptions(String[] args) {
    Map<String, String> options = new HashMap<>();
    for (int index = 0; index < args.length; index++) {
      String argument = args[index];
      switch (argument) {
        case "--help", "-h" -> options.put("help", "true");
        case "--show-source" -> options.put("show-source", "true");
        case "--query", "--json" -> {
          if (index + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for " + argument);
          }
          options.put(argument.substring(2), args[++index]);
        }
        default -> throw new IllegalArgumentException("Unknown argument: " + argument);
      }
    }
    return options;
  }

  private static String require(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required --" + name);
    }
    return value;
  }

  private static void printUsage(PrintStream output) {
    output.println(
        "Usage: java -jar json-stream-codegen-app.jar --query <query> [options]");
    output.println("  --json <object>   JSON object text; reads standard input when omitted");
    output.println("  --show-source     print generated Java source to standard error");
    output.println("  --help, -h        show this help");
  }
}
