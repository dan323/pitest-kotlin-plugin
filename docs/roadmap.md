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

## 0.3.0: keep precondition mutants, and test across Kotlin versions

### A. Keep `require` / `check` / `requireNotNull` mutants (`FKTINLINE`)

**Problem.** `require(x > 0)`, `check(ready)` and `requireNotNull(x)` are inline stdlib functions. The compiler expands them
to `if (!cond) throw IllegalArgumentException(...)` and maps the jump to `Preconditions.kt` in the SMAP, so `FKTINLINE`
drops its mutants as stdlib noise. But "what if the precondition never fired / always fired" is the same question as the
inlined predicate in `filter { }`, which `FKTINLINE` already keeps and relabels (`filter{} predicate: ...`). A test that
never passes an invalid argument is a real gap that we currently hide.

**Design.** Reuse the predicate relocation in `KotlinInlinedCodeFilter`. For an inlined range whose origin function is
`require`, `check`, `requireNotNull` or `checkNotNull` (the compiler writes `$i$f$require` / `$i$a$-require-...` local
variables), find the jump that consumes the condition, keep its mutants and report them on the call's own line as
`require{} precondition: ...`, keeping id and block. The compiler-generated message construction and the `throw` stay dropped (message text is not logic), but mutants in user-authored code inside the `lazyMessage` lambda stay eligible, as for any user lambda.
`error(...)` and `TODO()` are not preconditions and stay as they are.

**Decide first (measurement, before any code).** On http-client and one more project, count the mutants `FKTINLINE` drops
at these sites and their status when the filter is off. If most are `KILLED`, the filter is hiding signal and the change
is worth shipping on by default; if most `SURVIVE` because tests never trigger the failure, ship it on as well and say so
in the notes (the survivors are gaps); if there are almost none, drop the item.

**Tests.** A fixture with `require`, `check`, `requireNotNull` and a lambda message; assert the jump mutant is kept and
relabelled, that the generated message construction and the `throw` are still dropped while a user branch inside the message lambda (`require(x) { if (y) "a" else "b" }`) keeps its mutants, and that a user's own inline
`require`-like function is untouched (stdlib origins only, as for the other predicates). Add the sample to `e2e/`.

**Done when** the README table and `docs/user-guide.md` describe it and the measurement is recorded in the PR.

### B. Kotlin version matrix in CI

**Problem.** Every filter depends on the bytecode shape `kotlinc` emits, and shapes change between versions (the
`lateinit` throw moved to the jump target). We test on 2.4.21; users run 2.1 to 2.4 (http-client is on 2.2.20), so a
shape change would reach them silently.

**Design.** Compile the unit-test fixtures with each Kotlin version in a CI matrix (2.1.x, 2.2.x, latest 2.x), run the
same `FiltersTest`, mutator and `NonNullFilterTest` suites, and run the `e2e` job per version. The Kotlin version becomes a
Gradle property (`-PkotlinVersion=`) with the current one as the default. Tests that pin a shape (instruction counts,
`$lambda$N` names) must assert the behaviour instead, or be explicitly skipped for versions where the shape differs, with
the reason in the test.

**Done when** CI runs the matrix, every failure is either fixed in the filter or documented as unsupported in the README
("checked against Kotlin ..."), and the README lists the tested versions.

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

## Considered, not scheduled

- **Exact nullability from Kotlin metadata.** `FKTNONNULL` cannot prove a value non-null when it comes from a generic
  (`List<String>.get`) or an un-annotated private callee that is not final. The `@Metadata` annotation has the exact types,
  but reading it needs a metadata parser (`kotlin-metadata-jvm`), which breaks the "kotlin-stdlib only" rule unless it is
  shaded and relocated. Revisit if the number of unprovable equivalent mutants becomes a common complaint; the gain on
  http-client would be a handful of mutants.
- **Mutating default parameter values.** The constants live in `$default` methods, which PIT drops as line 0. Mutating
  them means bypassing that built-in filter, which is risky and niche.
- **`!!` mutator.** PIT's void-method-call removal already covers `Intrinsics.checkNotNull`.
