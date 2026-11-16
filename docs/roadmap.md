# Roadmap

What is planned per release. An item moves to the changelog (GitHub release notes) when it ships; an item that fails its
"decide" step is dropped here with the reason, so the next person does not redo the analysis.

Rules for every item: filters must not hide logic a test can kill (validate by the baseline status of the mutants they
remove: no `KILLED` mutant may be dropped by a default-on filter); bytecode only, no test results; the only runtime
dependency stays `kotlin-stdlib`; new filters are additive and keep existing feature names.

## 0.2.0 (merged, release pending)

`FKTNONNULL` (default on), `FKTMESSAGE` (opt-in), the `e2e` fix that makes `-Pmutators` reach PIT, and the documentation
of the `DEFAULTS` mutator-set trap. Release steps: bump the version in `build.gradle.kts`, the README `pitest(...)` line and
`e2e/build.gradle.kts`, tag `v0.2.0`, publish the GitHub release (see `CLAUDE.md`, "Publishing").

## 0.3.0: test across Kotlin versions

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

## 0.4.0: more stdlib swaps (`KOTLIN_STDLIB_SWAP`)

**Why.** On http-client the swap mutator generated 84 mutants and 24 survived, all of them test gaps (an unnamed request, an
indented line). That yield is far better than on the first corpora (mockito-kotlin 3, kotlin-result 0), so more pairs are
worth adding, driven by data rather than guesses.

**Candidates** (each must have a partner with the exact same descriptor, so the mutated class verifies): `isEmpty` <->
`isNotEmpty`, `isBlank` <-> `isNotBlank`, `plus` <-> `minus` on collections, `sorted` <-> `sortedDescending` (already in),
`reversed` (no-op swap, skipped), `sum`-style `minOf` <-> `maxOf`, `removePrefix` <-> `removeSuffix`, `indexOf` <->
`lastIndexOf` variants not yet covered, `filter`-family calls that are not inline (`filterIsInstance` is inline: skip).

**Process.** (1) Run the corpus with a throwaway extra table and count calls per candidate in real code; add only pairs that
occur. (2) For each pair, compile a fixture and read the real call site with `javap -c` (facade owner, `$default` variants).
(3) `StdlibSwapMutatorTest` already checks every table entry by reflection against the stdlib on the classpath; the new
entries must pass it. (4) Check for equivalents: a pair that is equivalent for a whole class of inputs (like `first` /
`last` under a `size == 1` guard) is fine, but a pair that is equivalent in practice (for example `plus` / `minus` of an
empty collection) must be listed in the user guide.

**Done when** the table, README and user guide list the new pairs and the PR records mutants/killed/survived per pair on
the corpora.

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
