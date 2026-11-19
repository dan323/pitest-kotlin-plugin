# Roadmap

What is planned per release. An item moves to the changelog (GitHub release notes) when it ships; an item that fails its
"decide" step is dropped here with the reason, so the next person does not redo the analysis.

Rules for every item: filters must not hide logic a test can kill (validate by the baseline status of the mutants they
remove: no `KILLED` mutant may be dropped by a default-on filter); bytecode only, no test results; the only runtime
dependency stays `kotlin-stdlib`; new filters are additive and keep existing feature names.

## 0.2.0 (released)

`FKTNONNULL` (default on), `FKTMESSAGE` (opt-in), the `e2e` fix that makes `-Pmutators` reach PIT, and the documentation
of the `DEFAULTS` mutator-set trap.

## 0.3.0: test across Kotlin versions (released)

### Kotlin version matrix in CI

**Problem.** Every filter depends on the bytecode shape `kotlinc` emits, and shapes change between versions (the
`lateinit` throw moved to the jump target). We test on 2.4.21; users run 2.1 to 2.4 (http-client is on 2.2.20), so a
shape change would reach them silently.

**Design.** Compile the unit-test fixtures with each Kotlin version in a CI matrix (2.1.x, 2.2.x, latest 2.x), run the
same `FiltersTest`, mutator and `NonNullFilterTest` suites, and run the `e2e` job per version. The Kotlin version becomes a
Gradle property (`-PkotlinVersion=`) with the current one as the default. Tests that pin a shape (instruction counts,
`$lambda$N` names) must assert the behaviour instead, or be explicitly skipped for versions where the shape differs, with
the reason in the test.

**Result (issue #6).** The matrix runs the unit suite and the `e2e` sample on Kotlin 2.1.20, 2.2.20, 2.3.20 and 2.4.21 (class
metadata confirms the fixtures are compiled by each). All 60 tests and every `e2e` assertion pass on all four with no
filter change, so no shape-specific skips were needed. The publishing plugin needs Kotlin 2.2+, so on older versions it is
not applied (`-PkotlinVersion` builds only compile and test; the published jar always uses the default).

## 0.4.0: more stdlib swaps (`KOTLIN_STDLIB_SWAP`, issue #7 done) `FKTWHEN` (issue #16 done) and `KOTLIN_REMOVE_DISTINCT` / `KOTLIN_REMOVE_SORTED` (issue #17 done)

**Why.** On http-client the swap mutator generated 84 mutants and 24 survived, all of them test gaps (an unnamed request, an
indented line). That yield is far better than on the first corpora (mockito-kotlin 3, kotlin-result 0), so more pairs are
worth adding, driven by data rather than guesses.

**Result (issue #7).** The calls in http-client's compiled classes were counted first; only pairs that occur and have the same
descriptor on both sides were added: `removePrefix` <-> `removeSuffix`, `trimStart` <-> `trimEnd` with a character set,
`indexOf` <-> `lastIndexOf` with a `Char`, `substringBefore` <-> `substringAfter` (and the `Last` variants) called with all
arguments, `startsWith` <-> `endsWith` with a `Char` or `CharSequence`. Candidates dropped because the call does not exist or
cannot be swapped: `isNotBlank`, `isNotEmpty`, `isEmpty` (inline, no call is left), `plus` <-> `minus` (different
descriptors: `plus(Collection, T)` vs `minus(Iterable, T)`), `startsWith` with an offset (no `endsWith` equivalent).
The table is checked against the stdlib by `StdlibSwapMutatorTest`.

On http-client the new pairs add 29 mutants (114 -> 143): 18 killed, 11 survive, all test gaps (`indexOf(':')` on a value
with a second colon, `rest.removeSuffix("()")`, a trailing `.` in a file name):

| Pair | Added | Killed | Survived |
|---|---|---|---|
| `indexOf` <-> `lastIndexOf` (char) | 9 | 3 | 6 |
| `removePrefix` <-> `removeSuffix` | 12 | 9 | 3 |
| `trimStart` <-> `trimEnd` (chars) | 5 | 3 | 2 |
| `substringAfter` <-> `substringBefore` (all arguments) | 3 | 3 | 0 |

### `KOTLIN_REMOVE_DISTINCT` / `KOTLIN_REMOVE_SORTED` (issue #17)

**Result.** `distinct`, `sorted`, `sortedDescending` and `sortedWith` are non-inline, so a call is left to mutate (`sortedBy` and
`distinctBy` are inline: not mutated). An `Iterable -> List` call is replaced by `toList()` on the receiver (`sortedWith` pops the
comparator first), which keeps the descriptor and verifies; a `Sequence -> Sequence` call is simply dropped. The tests execute
every mutant. Call sites in the local corpora are few: only http-client has any (2 `distinct`, about 8 `sortedWith`, 1 `sorted` in
compiled code), so the yield is small and the mutators are cheap, not a big win.

On http-client (`FKTINLINE` on) the two mutators generate 5 mutants: 2 killed, 2 survive, 1 `NO_COVERAGE`.
`VariableAnalysis.kt:133` (`environment.sorted().forEach { result.putIfAbsent(...) }`) is equivalent in practice: only the
insertion order changes. `FolderListing.kt:45` (`files.sortedWith(byName)`) is a test gap: no test checks the order of files.
`SettingsStore.kt:73` (`distinct()` in `bounded`) is untested code. Several `sortedWith` mutants in the stdlib's inlined `sortedBy` are
dropped by `FKTINLINE`, which is correct.

### `FKTWHEN` (issue #16)

**Result.** Shapes read with `javap` on Kotlin 2.1.20 to 2.4.21 (same on all): an enum `when` is a `tableswitch` whose default builds
`new NoWhenBranchMatchedException`; a sealed `when` is an `instanceof` chain whose last check jumps to that `new`. Executing the
mutants shows only two kinds are equivalent: the constructor call (unreachable) and the *never jump* `REMOVE_CONDITIONALS_*_IF` on the
last check. The opposite mutants of that check (`NEGATE_CONDITIONALS`, `*_ELSE`) change behaviour for the last subtype and are kept.
Consequence: with PIT's default and `DEFAULTS` mutators the filter drops nothing; it matters with `STRONGER`/`ALL`.

The throw is only treated as the compiler's when it is `new; dup; <init>()V; athrow` and every jump to it comes from a strictly
later line than the throw (the last branch's check; the throw carries the line of the `when` subject). A user-written throw (an
explicit `else` or `case`, `NoWhenBranchMatchedException("msg")`, one that is returned) is kept. An enum `when` is a `tableswitch`
on the same line as its throw, which looks exactly like a one-line `else -> throw`, so nothing is dropped for enum `when`s and a
generated throw on a single-line sealed `when` is kept too. Tests cover one-line `else` and `case` throws.

On http-client (core, `ALL` mutators, filter off vs on): 7502 -> 7487 mutants, 15 removed, none `KILLED` (9 `NO_COVERAGE`,
6 `SURVIVED`), none added. That meets the rule for a default-on filter.

## Measured and dropped

- **Keep `require` / `check` / `requireNotNull` mutants in `FKTINLINE`** (issue #5). The premise was that `FKTINLINE` drops
  the precondition jump as stdlib code. It does not: these functions are `@InlineOnly`, so the compiler gives their body the
  *call site's* line and the SMAP shows nothing to drop. Measured with `FKTINLINE` on vs off:
  - http-client (Kotlin 2.2.20, 1702 vs 1812 mutants; `FKTINLINE` drops 146, none in the classes with `require`): at
    `Model.kt:70-71` and `RequestTimeouts.kt:30` every conditional, boundary and `EQUAL_ELSE`/`ORDER_ELSE` mutant is
    kept (10 mutants, 8 of them killed, 2 survive).
  - A fixture on Kotlin 2.4.21 with `require` (with and without message), `check`, `requireNotNull` and `checkNotNull`:
    nothing dropped; the condition mutants and the mutants of a user branch inside the message lambda are all kept.
  Nothing to fix. (The relabelling of inlined `filter { }` predicates is needed only because those functions are not
  `@InlineOnly`.)

## Considered, not scheduled

- **Exact nullability from Kotlin metadata.** `FKTNONNULL` cannot prove a value non-null when it comes from a generic
  (`List<String>.get`) or an un-annotated private callee that is not final. The `@Metadata` annotation has the exact types,
  but reading it needs a metadata parser (`kotlin-metadata-jvm`), which breaks the "kotlin-stdlib only" rule unless it is
  shaded and relocated. Revisit if the number of unprovable equivalent mutants becomes a common complaint; the gain on
  http-client would be a handful of mutants.
- **Mutating default parameter values.** The constants live in `$default` methods, which PIT drops as line 0. Mutating
  them means bypassing that built-in filter, which is risky and niche.
- **`!!` mutator.** PIT's void-method-call removal already covers `Intrinsics.checkNotNull`.
