# JSON Stream Codegen

`json-stream-codegen` transforms raw JSON strings with a small SQL-like query language. Each query
is compiled to Java bytecode at runtime and executed as a single selective pass over the input: it
never calls `ObjectMapper.readTree` and never constructs a Jackson `JsonNode` input tree.

The runtime dependency graph contains only `jackson-core`. Jackson Databind is present only in the
optional benchmark profile, where a hand-written tree transform serves as the comparison baseline.

## Execution model

At query-compilation time, the engine:

1. parses and canonicalizes the query;
2. collects every distinct input path referenced by projections and the predicate;
3. builds an immutable trie for those paths;
4. generates Java source with direct numbered-slot access;
5. compiles and caches a stateless transformer.

For each JSON string, the transformer makes one Jackson Core token pass. Unknown fields and
containers are scanned for validity but not materialized. Referenced scalar values use the
module's small `JsonValue` types. A complete object or array is materialized only if the query
selects that container, passes it to a function, or otherwise needs it as a value. Output is
written directly to a `StringBuilder` rather than through a JSON tree.

This is selective parsing, not parsing avoidance: valid JSON cannot be queried correctly without
tokenizing it. The optimization removes whole-document object construction and most transient
allocation.

## Build and test

The build requires Maven 3.9.5+ and JDK 25. A full JDK — not a JRE — is required at runtime as well,
because queries are compiled by the in-process `javax.tools` compiler.

```bash
mvn clean verify
```

Confirm the production runtime graph:

```bash
mvn dependency:tree -Dscope=runtime
```

Expected non-project dependency:

```text
com.fasterxml.jackson.core:jackson-core
```

## Java API

```java
JsonTransformEngine engine = new JsonTransformEngine();
CompiledTransform compiled = engine.compile(
        "SELECT customer.name AS name, UPPER(city) AS city, "
                + "amount * 1.1 AS adjusted WHERE active = true");

String output = compiled.transform(
        "{\"customer\":{\"name\":\"Ada\"},\"city\":\"london\","
                + "\"amount\":10,\"active\":true,\"unused\":[1,2,3]}");
```

Result:

```json
{"name":"Ada","city":"LONDON","adjusted":11.0}
```

Compile once and retain `CompiledTransform` on the hot path. It and the engine are safe for
concurrent use. A predicate that is false or JSON-null returns Java `null`.

`compiled.selectedPaths()` exposes the exact paths materialized by the streaming reader, which is
useful for diagnostics and tests.

## CLI

```bash
java -jar target/json-stream-codegen-1.0.0-SNAPSHOT-app.jar \
  --query "SELECT UPPER(name) AS name" \
  --json '{"name":"Ada","unused":{"large":[1,2,3]}}'
```

Omit `--json` to read standard input. Add `--show-source` to inspect generated Java.

## Query language

The language supports:

- dotted object paths;
- zero-based array indexing and mixed paths such as `items[0].price` and `matrix[1][0]`;
- arithmetic `+`, `-`, `*`, `/`, `%`;
- comparisons `=`, `!=`, `<>`, `<`, `<=`, `>`, `>=`;
- three-valued `AND`, `OR`, and `NOT`;
- `WHERE` predicates;
- `UPPER`, `LOWER`, `CONCAT`, `COALESCE`, `LENGTH`, `ABS`, and `ROUND`;
- immutable custom functions over `JsonValue` arguments.

Computed projections require `AS`, including any projection that ends in an array index such as
`SELECT items[0] AS first`. Missing paths, explicit JSON null, an out-of-range array index, and
traversal through the wrong container type (indexing a non-array or keying a non-object) all
evaluate to JSON null. Arithmetic uses `BigDecimal` with `DECIMAL128`.

Array access is by fixed index only. Unsupported constructs remain joins, aggregation, wildcard or
slice projection over arrays (for example `items[*].price`), object or array constructors, mutation,
subqueries, and arbitrary Java calls.

## Custom functions

The engine intentionally exposes its own minimal values instead of Jackson nodes:

```java
JsonExpressionRuntime runtime = JsonExpressionRuntime.builder()
        .withStandardFunctions()
        .function("REVERSE", 1, arguments -> {
            JsonValue.StringValue value =
                    (JsonValue.StringValue) arguments.getFirst();
            return JsonValue.text(
                    new StringBuilder(value.value()).reverse().toString());
        })
        .build();

JsonTransformEngine engine = new JsonTransformEngine(runtime);
```

Custom functions must be thread-safe and must return a non-null `JsonValue`. Use
`JsonValue.nullValue()` for JSON null.

## Performance

The checked-in [benchmark report](docs/benchmark-results.md) compares complete string-in/string-out
operations. On the captured large sparse document, selective streaming was about 36% lower latency
and reduced allocation from about 101 KB to 1.3 KB per operation. On the small document it was
about 28% lower latency and used about half the allocation.

If an `ObjectNode` already exists or one parsed tree will serve several different queries, a
tree-based engine remains the appropriate choice. This library is optimized for one-pass processing
of raw strings, especially large documents with low field selectivity.

See [Architecture](docs/architecture.md) for correctness boundaries, caching, and extension rules.

## License

MIT. See [LICENSE](../LICENSE).
