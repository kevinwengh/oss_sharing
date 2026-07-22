# Critical review and resolutions

This review was performed after the first correct implementation and benchmark, then repeated on
the final diff. Findings are classified using merge-blocking severity rather than style priority.

## Resolved findings

### P1: Repeated pure expressions were evaluated repeatedly

The initial generator reused extracted field slots but did not eliminate repeated arithmetic or
logical subtrees. A query projecting `amount * 1.1` twice allocated and calculated two results.

Resolution: the generator now counts pure AST subtrees independently in predicates and
projections, emits ordered `commonN` locals, and deliberately excludes custom functions because
they may have observable invocation state. A generated-source test verifies the optimization.

### P1: Pathological flat expressions could overflow recursive generation

The parser limited parenthesis and unary recursion but a long left-associative operator chain could
still create a deeply nested AST. Later recursive formatter and generator passes could overflow the
stack.

Resolution: an iterative post-parse audit caps expression-tree depth at 64 and total AST nodes at
4,096 before canonicalization or generation. A regression test constructs a flat chain and verifies
early rejection.

### P1: Public number construction could emit invalid JSON

`BigDecimal` accepts some strings, such as a leading plus sign, that are not valid JSON number
lexemes. Exposing that spelling directly to serialization could let a custom function construct
invalid output.

Resolution: the public string-number factory validates the complete JSON number grammar, and the
`NumberValue` constructor is private. Tests cover leading plus, leading zero, incomplete fraction,
and valid signed scientific notation.

### P1: Unpaired UTF-16 surrogates were copied literally

An escaped unpaired surrogate accepted in input could be copied into the Java result string as an
unpaired code unit, making subsequent UTF-8 encoding lossy or implementation-dependent.

Resolution: the serializer preserves valid surrogate pairs and emits isolated high or low
surrogates as explicit `\uXXXX` escapes. A raw-input regression test verifies the behavior.

### P2: Duplicate-field reset walked the trie recursively on every selected field

Correct last-value-wins semantics initially reset descendants through maps and recursive calls.
Sampling showed this bookkeeping on the small-input hot path.

Resolution: each trie node precomputes its descendant slot array once during compilation. Runtime
reset is now a compact integer-array loop.

### P2: Output aliases were escaped and output arrays allocated on every call

Aliases are query identifiers and therefore compile-time constants. The initial generic serializer
re-escaped them and allocated an output array even for common one-to-three-field projections.

Resolution: generated classes retain pre-escaped key prefixes and call fixed-arity output writers
for up to three projections. Larger projections retain the generic array path.

### P2: Compatibility evidence was indirect

Expected strings covered individual behavior but did not prove alignment with a tree engine.

Resolution: differential cases against a Databind-based engine were run for paths, containers,
arithmetic, comparisons, nulls, standard functions, and predicate rejection. That engine is not
part of this repository, so the same cases are now pinned as expected outputs in
`QuerySemanticsTest`. Production runtime dependency analysis still reports only Jackson Core.

## Accepted architectural tradeoffs

- The complete token stream is consumed to reject malformed unselected content and trailing roots.
- Selecting a container materializes only that selected subtree in the module's immutable value
  model.
- Successful query classes and cache entries live for the engine lifetime; deployments accepting
  unbounded query text need admission control or scoped engine lifetimes.
- When an `ObjectNode` already exists or is reused across several queries, a tree engine's
  retained-tree hot path remains faster.
- Jackson Core remains the sole production JSON dependency because replacing its validated parser
  with a handwritten scanner would add substantial correctness and security risk without evidence
  that tokenization is the wrong abstraction.

## Final verdict

No P0 or unresolved P1/P2 findings remain. The final gate includes formatting, unit
tests, differential tests, clean Maven verification, runtime dependency inspection, executable-jar
smoke testing, generated-source inspection, GC allocation measurement, and end-to-end JMH results.
