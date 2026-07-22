# Generating, compiling, and running Java at runtime: an honest field report

*An experience-sharing write-up on building a small SQL-like query engine that compiles each
query into a bespoke Java class at runtime — what worked, what didn't, and when you should reach
for this pattern versus the alternatives.*

---

## The problem that led us here

We had a narrow but hot workload: take a raw JSON string, apply a small SQL-like transform
(`SELECT customer.name AS name, UPPER(city) AS city WHERE active = true`), and emit a new JSON
string. Millions of times. The same handful of queries, applied to an endless stream of documents.

The obvious implementation is an **interpreter**: parse the query into an AST once, then walk that
tree for every input document. It works, it's simple, and it's what most people should build first.
But a tree-walk pays interpretation overhead on every document — polymorphic dispatch on every AST
node, boxing, and a general-purpose JSON tree in memory.

Because the *query* is fixed and only the *data* changes, there's another option: treat the query
as a program, and **compile it once into a specialized Java class** whose `transform(String)` method
does exactly this query and nothing else. The per-document cost then collapses to straight-line code
the JIT can inline and optimize like any hand-written method.

This is the "generate Java source at runtime, compile it, load it, and call it" pattern. Here's what
we learned doing it for real.

## The pipeline

The whole thing is the standard JSR-199 (`javax.tools`) recipe. Six stages:

```
query text
   │  parse + canonicalize
   ▼
AST ──► Java *source* string   (a code generator emits a class implementing a known interface)
   │  javax.tools.JavaCompiler, in memory
   ▼
bytecode (bytes in a Map, never touches disk)
   │  custom ClassLoader.defineClass
   ▼
Class<?> ──► reflectively instantiate ONCE ──► cache the instance
   │
   ▼
hot path: call through the interface, no reflection
```

The pieces, concretely:

1. **A code generator** turns the validated AST into a Java source string. The generated class
   implements a stable interface known at build time:

   ```java
   public interface JsonTransformer {
       String transform(String input);   // null when the predicate doesn't match
   }
   ```

2. **In-memory compilation** via `ToolProvider.getSystemJavaCompiler()`. The trick is a
   `ForwardingJavaFileManager` that captures emitted bytecode into a `ByteArrayOutputStream`
   instead of writing `.class` files, plus a `SimpleJavaFileObject` that feeds the source from a
   string. No temp directory, no disk I/O.

3. **A per-query child ClassLoader** whose `findClass` calls `defineClass` on the captured bytes.

4. **Reflection exactly once** — to invoke the generated class's constructor. After that, every
   `transform` call is an ordinary virtual method call through the interface.

5. **Caching** so a given query compiles only once, keyed both by exact text and by a canonical
   (whitespace/case-normalized) form.

If you've seen the widely-copied `InMemoryJavaCompiler` gists, Baeldung's runtime-compilation
tutorial, or Spring's expression compiler, this will look familiar. That's the point: it's a
well-trodden pattern, and the value is in the details you add around it.

## What worked well

### 1. The interface boundary keeps reflection off the hot path

This is the single most important decision. The generated class implements an interface we control.
We reflect **once**, at instantiation, to call the constructor — and never again. Steady-state
execution is a plain interface dispatch that the JIT devirtualizes and inlines.

The common anti-pattern is to `Method.invoke(...)` on every call. That reintroduces reflection
overhead on the hot path and defeats the entire reason you compiled the code. If you take one thing
from this write-up: **generate a class that implements a known interface, and call through it.**

### 2. Specialization actually pays

On our workload — large, sparse documents where the query touches a few fields — the compiled path
was meaningfully faster and dramatically lower-allocation than the tree-walking baseline (roughly a
third less latency, and per-operation allocation dropping from ~100 KB to ~1 KB, because the
generated code reads only the selected fields and writes straight to a `StringBuilder` instead of
building a general JSON tree). Your mileage varies with query and document shape, but when the query
is fixed and the data volume is high, moving work from per-document to per-query is a real win.

### 3. Per-query classloaders make classes collectable

Each compiled query gets its own child `ClassLoader`. This matters: a class can only be unloaded
when its defining loader becomes unreachable. If you compile everything into one shared loader (as
many examples do), your generated classes live forever. Giving each query its own loader means the
class *can* be garbage-collected once nothing references it.

(Caveat below — "can be collected" and "is collected" are not the same thing.)

### 4. Semantic caching plus single-owner compilation

We cache on two keys: the raw query text, and a canonical form that normalizes whitespace and case
so `SELECT name` and `select   name` share one compiled class. Concurrent requests for the same
uncompiled query resolve through a shared `CompletableFuture`, so only one thread runs the compiler
and the rest wait on the result. A failed compile evicts the entry so it can be retried. None of
this is exotic, but it's the difference between a demo and something you can put under load.

### 5. String codegen can be injection-safe — if you're disciplined

Generating **source text** from user input is exactly the setup where injection bugs breed. We kept
it safe by never letting user-controlled text become code:

- output names are emitted as **escaped string literals**, never as identifiers;
- input field names become **integer array slots** (`fields[3]`), never emitted names;
- function names are either matched against a **fixed allow-list** before becoming a method call,
  or passed as **escaped string arguments** to a dispatcher;
- the generated class name is a **hash** of the query, not derived from its text.

The rule that made this tractable: *user input flows into the generated program only as data
(escaped literals or numeric indices), never as syntax.* If you can hold that line, string-based
codegen is defensible. If you can't, you shouldn't be concatenating source.

### 6. Readable generated code is a debugging superpower

Because we generate *source* (not raw bytecode), we could dump it (`--show-source`) and even assert
on it in tests. When a transform misbehaved, we read the generated method like any other Java. That
debuggability is the main reason to prefer source generation over hand-emitting bytecode.

## What bit us (the honest cons)

### 1. It requires a full JDK at runtime, not a JRE

`ToolProvider.getSystemJavaCompiler()` returns `null` on a JRE. Your service now depends on the
compiler being present — which rules out stripped `jlink` images and some minimal base containers,
and surprises anyone who assumed "it's just Java." We detect the null and fail loudly, but the
constraint is real and it's the first thing to check before choosing this approach. This one fact
disqualifies the pattern for a lot of deployment targets.

### 2. Compilation is slow, and the first call pays for it

Invoking `javac` in-process costs on the order of tens of milliseconds per query — orders of
magnitude more than parsing. Caching amortizes it across the life of the process, but:

- the **first** request for each query eats the full compile latency (a cold-start / tail-latency
  problem);
- we hand the compiler the whole application classpath (`System.getProperty("java.class.path")`) so
  generated code can see our runtime types, which means `javac` rescans that classpath on every
  compile. Fine at low query cardinality; a latency tax if you compile often.

If your queries are effectively unbounded and rarely repeat, the compile cost may never amortize and
this pattern is the wrong tool.

### 3. "Collectable" classes still leak if you cache them forever

We celebrated per-query classloaders for GC — then pinned every compiled instance in an
unbounded cache for the life of the engine. So in practice the classes never become unreachable.
Under unbounded distinct queries that's a slow **metaspace** leak, not heap, so it hides from the
usual memory dashboards until it doesn't. The honest fix is a bounded/LRU cache; until then,
"query admission" is a capacity concern you have to manage operationally. Document it, or better,
enforce it.

### 4. No sandbox — the query admission point *is* the security boundary

Generated bytecode runs with full application permissions. There's no seccomp, no SecurityManager
(and that's deprecated anyway), no resource governor around the generated method. For trusted,
first-party queries this is fine. For anything resembling user-supplied queries, understand that the
place you decide *which queries to compile* is your only line of defense. Treat it accordingly.

### 5. Errors move from parse time to compile time

A bug in the code generator surfaces as a **Java compilation error against generated source** — a
stack trace pointing at a line of code the user never wrote and can't see. You have to invest in
turning compiler diagnostics into intelligible messages (we collect diagnostics and format
line-referenced errors into the thrown exception). Budget for it; the raw `javac` output is not
something you want to show a caller.

## The fork in the road: how else could we have built this?

Runtime code generation has three mainstream flavors, and the right answer depends entirely on your
constraints:

| Approach | Needs JDK at runtime? | Compile speed | Debuggability | Best when |
|---|---|---|---|---|
| **Source + `javax.tools` (this project)** | **Yes** | Slow (real `javac`) | High (readable source) | Trusted queries, high reuse, you value readable output and don't control the deploy image tightly |
| **Bytecode gen (ASM, ByteBuddy)** | No | Fast | Low (you debug bytecode) | JRE-only / minimal images, tight latency budgets, high query churn |
| **Embedded compiler (Janino)** | No (ships its own) | Medium | Medium | You want source-like ergonomics without the JDK dependency, and can accept a language subset |

We chose the JSR-199 path because our queries are trusted and highly repeated, and because being
able to *read* the generated code was worth more to us than shaving milliseconds off a
once-per-query compile. Had we needed to run on a JRE, or faced unbounded one-shot queries, we'd
have gone to ByteBuddy and accepted worse debuggability.

There's also the option we didn't need but should name: **don't generate code at all.** If your
per-item cost is dominated by I/O, or your throughput is modest, a plain AST interpreter is simpler,
has none of these downsides, and is where you should start. Codegen earns its complexity only when
the query is fixed, the data volume is large, and the per-document overhead genuinely matters.

## Takeaways

- **Generate a class that implements a known interface, and call through it.** Reflection belongs at
  instantiation, never on the hot path.
- **Let user input in as data, never as syntax.** Escaped literals and numeric indices are safe;
  interpolated identifiers are how you get injection.
- **Prefer generating source over bytecode** unless a hard constraint (no JDK, latency, churn) forces
  your hand — readability during debugging is worth a lot.
- **The JDK-at-runtime requirement is a deployment decision, not a detail.** Verify it before you
  commit to `javax.tools`.
- **Cache deliberately, and bound the cache.** "Collectable" classes you never release are still a
  leak — in metaspace, where you won't be looking.
- **Codegen is a scaling optimization, not a default.** Reach for it when profiling says the
  per-item interpretation cost is your bottleneck; otherwise an interpreter is the honest answer.

The pattern is powerful and, done carefully, more approachable than its reputation suggests — the
`javax.tools` API does the heavy lifting. But every one of its benefits comes with a matching cost,
and the engineering is mostly in the details you build *around* the compiler, not in calling it.
