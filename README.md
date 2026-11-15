# PIT Kotlin plugin

[![CI](https://github.com/dan323/pitest-kotlin-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/dan323/pitest-kotlin-plugin/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A free plugin for [PIT](https://pitest.org) mutation testing that makes it work better on Kotlin. PIT mutates JVM
bytecode and knows nothing about Kotlin, so on Kotlin code it reports a lot of mutants that no test could or should
kill, and it misses some that matter. This plugin does two things:

1. **Filters** drop mutants in compiler-generated code (inlined stdlib code, null checks, coroutine state machines, ...).
2. **Mutators** add Kotlin-specific mutants (`?:`, `?.`, stdlib method swaps).

Filters are on as soon as the plugin is on PIT's classpath. Mutators are opt-in.

## Use it with Gradle

With the [gradle-pitest-plugin](https://gradle-pitest-plugin.solidsoft.info/):

```kotlin
dependencies {
    pitest("io.github.dan323:pitest-kotlin-plugin:0.1.0")
}

pitest {
    pitestVersion = "1.30.2"
    mutators = setOf("DEFAULTS", "KOTLIN")   // optional: adds the Kotlin mutators below
    // features = setOf("-FKTINLINE")         // optional: switch a filter off, or pass it options
}
```

Requires PIT 1.30.2 or later on a JVM 17+ runtime. The plugin's only runtime dependency is `kotlin-stdlib`, which
Gradle resolves for you.

## Filters

Each is a PIT feature, on by default. Switch one off with `-NAME`, e.g. `features = setOf("-FKTCORO")`.

| Feature | Drops |
|---|---|
| `FKTINLINE` | Mutants in code the compiler inlined from the Kotlin stdlib (`map`, `filter`, `let`, ...). Your own lambdas are still mutated, and so is the jump that consumes an inlined predicate's result (`filter { }`, `any { }`, `all { }`...), reported on your lambda's line as `filter{} predicate: ...`. |
| `FKTINTRINSICS` | Compiler-generated null checks (`checkNotNullExpressionValue`, ...), the `lateinit` guard, and `InlineMarker` calls. Your own `!!` is kept. |
| `FKTCORO` | Coroutine state-machine plumbing: `throwOnFailure`, `COROUTINE_SUSPENDED` comparisons and returns. |
| `FKTUNIT` | Return-value mutants on `return Unit.INSTANCE`. |
| `FKTFORINLINE` | Everything in `name$$forInline` methods, copies of inline suspend functions that nothing calls. |
| `FKTMESSAGE` (**off by default**, enable with `+FKTMESSAGE`) | The "never null" mutants on `Throwable.message`: `e.message ?: "x"` (`KOTLIN_ELVIS_NEVER_DEFAULT` and PIT's identical `REMOVE_CONDITIONALS_EQUAL_ELSE`) and `e.message?.f()` (`KOTLIN_SAFECALL_NEVER_NULL`). Only a test that throws an exception without a message can kill them. The "always default" mutant stays: any test that asserts the failure message kills it. It is a heuristic (a real `NullPointerException()` has no message), hence opt-in. |
| `FKTELVISDEDUP` | PIT's own conditional mutants on an elvis or safe-call jump that the Kotlin mutators below duplicate. |

`FKTINLINE` options, passed as `+FKTINLINE(option[value])`:

- `scope[all]`: also drop your own project's inlined code (default `stdlib`: only `kotlin/` and `kotlinx/`).
- `prefixes[arrow/]`: treat more packages as library code.
- `predicates[drop]`: drop the inlined predicate jumps as well.

PIT 1.30.2 already drops every mutant on line 0 of a `.kt` file, which covers data-class generated methods,
`$default` methods, `$WhenMappings` and parameter null checks, so this plugin does not repeat that.

## Mutators

Select them with `mutators = setOf("DEFAULTS", "KOTLIN")`, or by name.

| Mutator | `a ?: b` / `a?.f()` / call becomes | Killed by |
|---|---|---|
| `KOTLIN_ELVIS_ALWAYS_DEFAULT` | `a ?: b` always evaluates `b` | a test with a non-null `a` |
| `KOTLIN_ELVIS_NEVER_DEFAULT` | `a ?: b` always yields `a`, even when null | a test with a null `a` |
| `KOTLIN_SAFECALL_NEVER_NULL` | `a?.f()` calls `f` on a null `a` | a test with a null receiver |
| `KOTLIN_STDLIB_SWAP` | `first` <-> `last`, `take` <-> `drop`, `startsWith` <-> `endsWith`, `coerceAtLeast` <-> `coerceAtMost`, `Math.max` <-> `min`, ... | a test sensitive to the swap |

PIT already ships mutants equivalent to the "never default" elvis (`REMOVE_CONDITIONALS_EQUAL_ELSE`, in `DEFAULTS`)
and to "always null" for a safe call. What `DEFAULTS` lacks, and these add, is the other side of each, with names
that say which side no test exercises. `FKTELVISDEDUP` then removes the duplicates.

## What to expect

Measured with PIT 1.30.2 on two open-source projects (all filters on, `DEFAULTS` + `KOTLIN`):

| Project | Mutants before -> after | What changed |
|---|---|---|
| [mockito-kotlin](https://github.com/mockito/mockito-kotlin) (Java-interop heavy) | 342 -> 268 | Mutation score 52% -> 60%; run time 45 s -> about 20 s |
| [kotlin-result](https://github.com/michaelbull/kotlin-result) (inline heavy) | 1384 -> 478 | Almost all mutants are still uncovered, see below |

Numbers depend on the project and on test flakiness (about 1% of mutants flipped between identical runs).

## Limitations

- **PIT cannot see logic inside `inline` functions.** A caller gets a copy of the body, so tests run the copy in the
  test class and the declaring class shows as uncovered. On inline-heavy code most mutants stay `NO_COVERAGE` whatever
  this plugin does.
- `@InlineOnly` functions that delegate to another inline function (such as `find`) are attributed to the call site's
  line, so `FKTINLINE` cannot tell their loop code from yours.
- Some mutants are equivalent and bytecode cannot tell: `first` <-> `last` on one-element lists, and
  `x?.let { setter(it) }` where the setter accepts null.
- Bytecode patterns were checked against Kotlin 2.1.20 and 2.4.21. Other versions may emit different shapes.

## Build

```
./gradlew test            # unit tests, including mutants executed through the JVM verifier
./gradlew jar
./gradlew publishToMavenLocal
```

`e2e/` is a separate Gradle build that runs PIT with the plugin on a small sample.

## License

Apache License 2.0, see [LICENSE](LICENSE).
