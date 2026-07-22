# End-to-end benchmark results

## Question measured

The benchmark measures the actual boundary for raw input:

```text
Selective engine: JSON String -> selective token scan -> expressions -> JSON String
Tree baseline:    JSON String -> ObjectMapper tree -> expressions -> serialize -> JSON String
```

The tree baseline's retained-tree hot path is also measured to show why these results should not
be interpreted as making streaming preferable when a tree already exists.

> The numbers below were captured while the tree baseline was a separate Databind-based transform
> engine developed alongside this one. That engine is not part of this repository, so
> `JsonTransformBenchmark` now uses an equivalent hand-written `ObjectNode` projection as the
> baseline. Re-run the benchmark before quoting these figures in a new environment.

## Environment and method

Captured July 5, 2026:

| Component | Value |
|---|---|
| Hardware | Apple M1 Pro, 8 cores, 32 GB |
| OS | macOS 26.5.1, arm64 |
| JVM | OpenJDK 25.0.3 |
| JMH | 1.37 |
| Forks / threads | 1 / 1 |
| Warmup | 3 iterations × 500 ms |
| Measurement | 5 iterations × 1 second |
| Profiler | JMH GC allocation profiler |

The small input contains only the four referenced values. The large sparse input contains 200
unselected arrays with 20 numbers each before those same four values. Both execute:

```sql
SELECT customer.name AS customerName,
       UPPER(city) AS city,
       ROUND(amount * 1.1, 2) AS adjustedAmount
WHERE active = true
```

## Integrated comparison

| Workload | Mean | Allocation | Relative interpretation |
|---|---:|---:|---|
| Existing parsed-tree hot path | 124.897 ns/op | 528 B/op | fastest when input is already a tree |
| Selective streaming, small | 453.047 ns/op | 1,288 B/op | full string-in/string-out |
| Full tree, small | 628.223 ns/op | 2,704 B/op | selective is ~27.9% lower latency, ~52.4% less allocation |
| Selective streaming, large sparse | 48.172 µs/op | 1,288 B/op | allocation stays nearly constant |
| Full tree, large sparse | 75.832 µs/op | 101,153 B/op | selective is ~36.5% lower latency, ~98.7% less allocation |

The selective engine still tokenizes the full large document to validate JSON. Its primary scaling
benefit is that unselected values do not become Java objects. The stack sampler attributed most of
the large-input time to Jackson Core's `skipChildren` token scan, confirming that expression
evaluation is no longer the bottleneck for sparse queries.

## Optimization evidence

After the first correct streaming implementation, profiling identified recursive duplicate-field
slot resets and output-key escaping on every invocation. Precomputing descendant slot arrays,
pre-escaping identifier output prefixes, and adding fixed-arity output writers changed the small
case from 563.434 ns/op and 1,424 B/op to 453.047 ns/op and 1,288 B/op: about 19.6% lower latency and
9.6% lower allocation.

Large-input latency before and after that micro-optimization was effectively unchanged; allocation
fell from about 1,345 B/op to 1,288 B/op. This is expected because token scanning dominates the
large case.

## Reproduce

```bash
mvn -Pbenchmark -DskipTests clean package

java -jar target/json-stream-codegen-1.0.0-SNAPSHOT-benchmarks.jar \
  -prof gc -rf json \
  -rff target/jmh-result.json
```

The `benchmark` profile alone brings Jackson Databind onto the benchmark classpath. It is not a
production dependency of this library.

## Limitations

- One fork is useful local evidence, not a universal hardware-independent claim.
- The large workload represents high sparsity; projecting entire containers narrows the advantage.
- The comparison includes output serialization for both engines but their output representations
  differ internally.
- If one parsed tree serves several queries, amortized tree construction can outperform reparsing
  the string for each query.
- JMH 1.37 reports a deprecated `Unsafe` warning on JDK 25; benchmark execution completes normally.
