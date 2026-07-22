/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream.internal;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.kevinwen.jsonstream.JsonValue;
import com.kevinwen.jsonstream.QueryEvaluationException;
import com.kevinwen.jsonstream.internal.QueryModel.Index;
import com.kevinwen.jsonstream.internal.QueryModel.Key;
import com.kevinwen.jsonstream.internal.QueryModel.PathStep;

/** Streams a JSON object once and materializes only paths referenced by a compiled query. */
public final class SelectiveJsonReader {
  private static final JsonFactory FACTORY =
      JsonFactory.builder()
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(1_000)
                  .maxStringLength(20_000_000)
                  .maxDocumentLength(100_000_000)
                  .build())
          .build();

  private final TrieNode root = new TrieNode();
  private final int valueCount;

  public SelectiveJsonReader(List<List<PathStep>> paths) {
    valueCount = paths.size();
    for (int slot = 0; slot < paths.size(); slot++) {
      TrieNode current = root;
      for (PathStep step : paths.get(slot)) {
        current =
            switch (step) {
              case Key key ->
                  current.objectChildren.computeIfAbsent(key.name(), ignored -> new TrieNode());
              case Index index ->
                  current.indexChildren.computeIfAbsent(index.index(), ignored -> new TrieNode());
            };
      }
      current.slot = slot;
    }
    root.freeze();
  }

  public JsonValue[] read(String input) {
    JsonValue[] values = new JsonValue[valueCount];
    Arrays.fill(values, JsonValue.nullValue());
    try (JsonParser parser = FACTORY.createParser(input)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        throw new QueryEvaluationException("Input JSON must be an object");
      }
      scanObject(parser, root, values);
      if (parser.nextToken() != null) {
        throw new QueryEvaluationException("Input contains trailing JSON content");
      }
      return values;
    } catch (QueryEvaluationException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new QueryEvaluationException("Invalid input JSON: " + e.getMessage(), e);
    }
  }

  private static void scanObject(JsonParser parser, TrieNode node, JsonValue[] values)
      throws IOException {
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      String fieldName = parser.currentName();
      JsonToken valueToken = parser.nextToken();
      TrieNode selected = node.objectChildren.get(fieldName);
      if (selected == null) {
        parser.skipChildren();
        continue;
      }
      descend(parser, selected, valueToken, values);
    }
  }

  private static void scanArray(JsonParser parser, TrieNode node, JsonValue[] values)
      throws IOException {
    int position = 0;
    JsonToken elementToken;
    while ((elementToken = parser.nextToken()) != JsonToken.END_ARRAY) {
      TrieNode selected = node.indexChildren.get(position++);
      if (selected == null) {
        parser.skipChildren();
        continue;
      }
      descend(parser, selected, elementToken, values);
    }
  }

  private static void descend(
      JsonParser parser, TrieNode selected, JsonToken valueToken, JsonValue[] values)
      throws IOException {
    reset(selected, values);
    if (selected.slot >= 0) {
      JsonValue value = readValue(parser, valueToken);
      values[selected.slot] = value;
      populateDescendants(selected, value, values);
    } else if (valueToken == JsonToken.START_OBJECT) {
      scanObject(parser, selected, values);
    } else if (valueToken == JsonToken.START_ARRAY) {
      scanArray(parser, selected, values);
    } else {
      parser.skipChildren();
    }
  }

  private static JsonValue readValue(JsonParser parser, JsonToken token) throws IOException {
    return switch (token) {
      case VALUE_NULL -> JsonValue.nullValue();
      case VALUE_TRUE -> JsonValue.bool(true);
      case VALUE_FALSE -> JsonValue.bool(false);
      case VALUE_STRING -> JsonValue.text(parser.getText());
      case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> JsonValue.number(parser.getText());
      case START_ARRAY -> readArray(parser);
      case START_OBJECT -> readObject(parser);
      default -> throw new QueryEvaluationException("Unexpected JSON token: " + token);
    };
  }

  private static JsonValue readArray(JsonParser parser) throws IOException {
    List<JsonValue> values = JsonValue.mutableArray();
    JsonToken token;
    while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
      values.add(readValue(parser, token));
    }
    return JsonValue.array(values);
  }

  private static JsonValue readObject(JsonParser parser) throws IOException {
    Map<String, JsonValue> fields = JsonValue.mutableObject();
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      String name = parser.currentName();
      fields.put(name, readValue(parser, parser.nextToken()));
    }
    return JsonValue.object(fields);
  }

  private static void populateDescendants(
      TrieNode node, JsonValue value, JsonValue[] selectedValues) {
    for (Map.Entry<String, TrieNode> entry : node.objectChildren.entrySet()) {
      TrieNode child = entry.getValue();
      JsonValue childValue =
          value instanceof JsonValue.ObjectValue object
              ? object.get(entry.getKey())
              : JsonValue.nullValue();
      if (child.slot >= 0) {
        selectedValues[child.slot] = childValue;
      }
      populateDescendants(child, childValue, selectedValues);
    }
    for (Map.Entry<Integer, TrieNode> entry : node.indexChildren.entrySet()) {
      TrieNode child = entry.getValue();
      JsonValue childValue =
          value instanceof JsonValue.ArrayValue array
              ? array.get(entry.getKey())
              : JsonValue.nullValue();
      if (child.slot >= 0) {
        selectedValues[child.slot] = childValue;
      }
      populateDescendants(child, childValue, selectedValues);
    }
  }

  private static void reset(TrieNode node, JsonValue[] values) {
    for (int slot : node.subtreeSlots) {
      values[slot] = JsonValue.nullValue();
    }
  }

  private static final class TrieNode {
    private final Map<String, TrieNode> objectChildren = new LinkedHashMap<>();
    private final Map<Integer, TrieNode> indexChildren = new LinkedHashMap<>();
    private int slot = -1;
    private int[] subtreeSlots;

    private int[] freeze() {
      int size = slot >= 0 ? 1 : 0;
      for (TrieNode child : objectChildren.values()) {
        size += child.freeze().length;
      }
      for (TrieNode child : indexChildren.values()) {
        size += child.freeze().length;
      }
      subtreeSlots = new int[size];
      int offset = 0;
      if (slot >= 0) {
        subtreeSlots[offset++] = slot;
      }
      for (TrieNode child : objectChildren.values()) {
        System.arraycopy(child.subtreeSlots, 0, subtreeSlots, offset, child.subtreeSlots.length);
        offset += child.subtreeSlots.length;
      }
      for (TrieNode child : indexChildren.values()) {
        System.arraycopy(child.subtreeSlots, 0, subtreeSlots, offset, child.subtreeSlots.length);
        offset += child.subtreeSlots.length;
      }
      return subtreeSlots;
    }
  }
}
