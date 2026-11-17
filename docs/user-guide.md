# User guide

Details behind the [README](../README.md): how each filter and mutator behaves, why, and what it cannot do.

## Mutator sets: `DEFAULTS` is not "nothing set"

In PIT 1.30 the `DEFAULTS` group is not what PIT runs when `mutators` is unset. Unset gives the classic set
(`NEGATE_CONDITIONALS`, `CONDITIONALS_BOUNDARY`, `MATH`, `INCREMENTS`, `INVERT_NEGS`, `VOID_METHOD_CALLS`, the `*_RETURNS`
mutators). `DEFAULTS` swaps `NEGATE_CONDITIONALS` for `REMOVE_CONDITIONALS_EQUAL_ELSE` / `_ORDER_ELSE`. Writing
`mutators = setOf("DEFAULTS", "KOTLIN")` therefore changes your baseline score before any Kotlin mutator runs. List the
classic mutators by name and add `"KOTLIN"` (see the README snippet).

## Filters

Each is a PIT feature. Switch one off with `-NAME`, on with `+NAME`, e.g. `features = setOf("-FKTCORO", "+FKTMESSAGE")`.

- **`FKTINLINE`** drops mutants in code the compiler inlined from the Kotlin stdlib, found through the class's SMAP.
  Options: `+FKTINLINE(scope[all])` also drops your own project's inlined code (default: only `kotlin/` and `kotlinx/`,
  because your own `inline` functions, especially `inline reified`, only ever run through inlined copies, so dropping them
  would hide real logic); `prefixes[arrow/]` treats more packages as library code; `predicates[drop]` drops the inlined
  predicate jumps too.
  By default the jump that consumes an inlined predicate (`filter { }`, `any { }`, `all`, `count`, `first`, `takeWhile`,
  `partition`...) is **kept** and reported on your lambda's line as `filter{} predicate: ...`: it is the "does the predicate
  matter" question.
- **`FKTINTRINSICS`**: compiler null checks (`checkNotNullExpressionValue`, `checkNotNullParameter`...), the `lateinit`
  guard and `InlineMarker` calls. Your own `!!` is kept.
- **`FKTCORO`**: coroutine state-machine plumbing (`throwOnFailure`, `COROUTINE_SUSPENDED` comparisons and returns).
- **`FKTUNIT`**: return-value mutants on `return Unit.INSTANCE`.
- **`FKTFORINLINE`**: everything in `name$$forInline` methods (copies of inline suspend functions nothing calls).
- **`FKTELVISDEDUP`**: PIT's own conditional mutants on an elvis or safe-call jump that this plugin's mutators duplicate.
  Each is dropped only when its equivalent from this plugin is present at the same instruction.
- **`FKTNONNULL`**: the never-null side of an elvis or safe call (`KOTLIN_ELVIS_NEVER_DEFAULT`,
  `KOTLIN_SAFECALL_NEVER_NULL`, PIT's identical `REMOVE_CONDITIONALS_EQUAL_ELSE` / `_EQUAL_IF`) when the value cannot be
  null. Kotlin emits the check even for non-null values, and in `a?.b ?: d` or `a?.b()?.c()` the later jumps only see what
  the previous call returned. A value counts as non-null when it comes from a `@NotNull` method (Kotlin writes it on every
  non-private non-null return, so the whole stdlib), a string constant or concatenation, a `checkNotNullExpressionValue`, or
  a private/static/final method of your own whose every return is one of these. Anything it cannot prove is kept, so it is
  safe to leave on.
- **`FKTMESSAGE`** (opt-in): the never-null side of `e.message ?: x` and `e.message?.f()`. The mutant that assumes the
  message is never null can only be killed by a test that throws an exception without a message. The always-default side
  stays: any test asserting the message kills it. It is a heuristic (a real `NullPointerException()` has no message), so it
  is off by default; on one project it hid a mutant a test had killed.

PIT 1.30.2 already drops every mutant on line 0 of a `.kt` file (data-class methods, `$default`, `$WhenMappings`,
parameter null checks), so this plugin does not repeat that.

## Mutators

| Mutator | What it does | PIT built-in with the same effect |
|---|---|---|
| `KOTLIN_ELVIS_NEVER_DEFAULT` | `a ?: b` yields `a`, even when null | `REMOVE_CONDITIONALS_EQUAL_ELSE` (`DEFAULTS`) |
| `KOTLIN_ELVIS_ALWAYS_DEFAULT` | `a ?: b` always yields `b` | `REMOVE_CONDITIONALS_EQUAL_IF` (`STRONGER`) |
| `KOTLIN_SAFECALL_NEVER_NULL` | `a?.f()` calls `f` on a null `a` | `REMOVE_CONDITIONALS_EQUAL_IF` (`STRONGER`) |
| `KOTLIN_STDLIB_SWAP` | `first`<->`last`, `take`<->`drop`, `startsWith`<->`endsWith`, `coerceAtLeast`<->`coerceAtMost`, `removePrefix`<->`removeSuffix`, `indexOf`<->`lastIndexOf`, `substringBefore`<->`substringAfter`, `trimStart`<->`trimEnd`, `Math.max`<->`min`... | none |

`DEFAULTS` lacks the always-default side of an elvis and the never-null side of a safe call; the Kotlin mutators add them
with names that say which side no test exercises. A survivor on `*_NEVER_DEFAULT` means no test supplies a null; on
`*_ALWAYS_DEFAULT`, no test supplies a non-null. The stdlib swap needs both operands to share a descriptor and only
applies to real calls: inline functions (`filter`, `any`, `map`, `isNotBlank`...) leave none.

Some swaps are equivalent for a whole class of inputs, not for the code: `indexOf` <-> `lastIndexOf` when the searched
character occurs once, `first` <-> `last` on a one-element list, `removePrefix` <-> `removeSuffix` when only one of the two
can match. A survivor there means no test uses an input where they differ (a header value that contains a second `:`).

## Reading survivors

Most survivors of the Kotlin mutators are **test gaps**, not noise. On one 1,700-mutant project about 90 of 112
survivors were inputs no test supplied (an unnamed request, a missing header, an indented line). In particular
`line.length - line.trimStart().length` swapped to `trimEnd` is not equivalent: it differs for every indented line.
Classify a survivor by asking which input kills it before you reach for a filter.

## Limitations

- **PIT cannot see logic inside `inline` functions.** A caller gets a copy of the body, so tests run the copy in the test
  class and the declaring class shows as uncovered. On inline-heavy code most mutants stay `NO_COVERAGE` whatever this
  plugin does.
- `@InlineOnly` functions (such as `find`) are attributed to the call site's line, so `FKTINLINE` cannot tell their loop
  code from yours.
- Some mutants are equivalent and bytecode cannot tell: `first`<->`last` on a one-element list, `x?.let { setter(it) }`
  where the setter accepts null, and the link after a generic `List<String>.get` in a safe-call chain.
- Bytecode patterns depend on the Kotlin compiler. CI compiles the test fixtures and the `e2e` sample with Kotlin 2.1.20, 2.2.20, 2.3.20 and 2.4.21 and runs every check on each. Other versions may emit different shapes.
