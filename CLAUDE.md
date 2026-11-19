# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A free PIT (pitest) plugin that improves mutation testing of Kotlin. PIT works on JVM bytecode, never Kotlin source, so everything is written against what `kotlinc` emits. Package `io.github.dan323.pitkotlin`. Two parts: **filters** (`MutationInterceptor`s) that drop noisy mutants and opt-in **mutators** (`KOTLIN` group). What each does for users: `README.md` and `docs/user-guide.md` (keep user-facing text there, not here).

## Commands

```
./gradlew test                                   # all tests
./gradlew test --tests '*FiltersTest.inlined*'   # one test (backtick names: match a substring)
./gradlew test -PkotlinVersion=2.1.20            # fixtures compiled with another Kotlin (CI matrix: 2.1.20, 2.2.20, 2.3.20, 2.4.21)
./gradlew publishToMavenLocal                    # needed before the e2e build
./gradlew -p e2e pitest -Pmutators=DEFAULTS,KOTLIN [-Pfeatures=-FKTNONNULL,+FKTMESSAGE] [-PreportName=reports/x]
```

`e2e/` is a separate build that consumes the **published coordinates** from `mavenLocal()` (use `-Dmaven.repo.local=<dir>` on both to keep `~/.m2` clean). Diff two `mutations.xml` files to see exactly what a filter removed. Without the `mutators` line in `e2e/build.gradle.kts` the KOTLIN mutators silently do not run.

## Architecture

- `KotlinFilterSupport.kt`: `KotlinMutationFilter` (subclasses implement `shouldDrop(MutationDetails)`) and `KotlinFilterFactory` (supplies the PIT `Feature`, `onByDefault`). One filter + factory per file; each factory must be listed in `META-INF/services/org.pitest.mutationtest.build.MutationInterceptorFactory` (`ServiceRegistrationTest` checks the names).
- Null-site filters share `NullSites.kt` (which mutants are the "never null" side of a `dup; ifnull/ifnonnull`); `KotlinNonNull.kt` proves a value non-null from bytecode.
- Mutators (`KotlinElvisMutators.kt`, `KotlinStdlibSwapMutator.kt`, `KotlinRemoveMutators.kt`) are plain classes with a no-arg constructor, listed in `META-INF/services` as `MethodMutatorFactory` / `MutatorGroup`; PIT's `Mutator` loads them, no custom engine.
- Mutators use PIT's **shaded ASM** (`org.pitest.reloc.asm`); interceptors use `org.objectweb.asm` through `ClassTree`.
- PIT's `feature.name()` is lower-case, users toggle `+FKTINLINE` / `-FKTINLINE`. Heuristic filters must be `onByDefault = false`.
- PIT is `compileOnly`; `kotlin-stdlib` is the only runtime dependency and must be on PIT's plugin classpath (`e2e` adds it).
- Target JVM 17: set both `jvmTarget` and `options.release = 17`, also in `e2e`.

## Lessons (not obvious from the code)

- **Check what PIT already does** before building a mutator or filter. PIT drops every line-0 mutant in `.kt` files (data classes, `$default`, `$WhenMappings`). `REMOVE_CONDITIONALS_EQUAL_ELSE` is byte-identical to our NEVER_DEFAULT, `EQUAL_IF` to ALWAYS_DEFAULT / SAFECALL_NEVER_NULL; the XML report may label them "negated conditional". Dump mutants at one instruction across `DEFAULTS`/`STRONGER`/`ALL` and compare class bytes.
- **Validate a filter by the status of what it removes.** Dropping `KILLED` mutants means the pattern may be real logic (a rule on `aload; areturn` after `throwOnFailure` was reverted for that). Compare runs with other filters off against a baseline `mutations.xml`.
- **Classify survivors before filtering.** Most Kotlin-mutator survivors on a real project were test gaps; `trimStart` vs `trimEnd` in an indent computation is not equivalent.
- **A mutator must register in the coverage block tests reach.** PIT runs only tests that covered the block current at registration; elvis mutants first registered at the default branch and showed false survivors. They register at the jump; `ElvisMutatorsTest` asserts the block. Lookahead means buffering events.
- **Test mutators by executing them** (`MutantSet` loads mutated bytes in a child-first loader, so the verifier runs). Test filters on fixtures in `src/test/kotlin/fixtures/Fixtures.kt` via `FilterTestSupport`, asserting what is dropped *and* what is kept.
- `MutationDetails.getInstructionIndex()` indexes the full ASM list including labels/frames; use `MethodTree.instructions()`. A `nop` (inline `let`) can sit between a call and its `dup`.
- Bytecode shapes depend on the Kotlin version: verify new patterns with `javap -p -c` on the compiled fixtures, and run the suite with `-PkotlinVersion=` across the CI matrix. Pass `-PkotlinVersion` to the `e2e` build too (`-p e2e`). The publish plugin needs Kotlin 2.2+, so `build.gradle.kts` skips it on older versions.
- Interceptor options: `InterceptorParameters.getList` returns **null** when other options are set but that key is absent; `getString` only through `Optional`. Rewriting `MutationDetails` in an interceptor is fine (id, file, description, line, blocks).
- Kotlin 2.x: non-suspend lambdas are `invokedynamic` + `name$lambda$N` on the enclosing class; suspend lambdas get their own class. Stdlib facade call sites name `kotlin/collections/CollectionsKt`, not `___CollectionsKt` (stdlib swap table; `StdlibSwapMutatorTest` checks every entry by reflection).
- Inlined predicate lambdas are found through the `$i$a$-<fn>-...` local variable; the jump after it is relabelled by `FKTINLINE`. This needs the whole `ClassTree`, so it cannot be a mutator.
- Mutant status can flip between identical runs on state-dependent tests; compare per site before blaming a change.
- zsh does not word-split unquoted variables in measurement scripts.

## Publishing

- `com.vanniktech.maven.publish`: coordinates `io.github.dan323:pitest-kotlin-plugin`, Apache-2.0, repo `github.com/dan323/pitest-kotlin-plugin`. The published POM must not list PIT; it must list `kotlin-stdlib`.
- Signing only when `signingInMemoryKey` / `signing.keyId` is set. Credentials come from `ORG_GRADLE_PROJECT_mavenCentral*` env vars, never files. Do not run `publishToMavenCentral` or push without the user's go-ahead.
- `.github/workflows/publish.yml` publishes on a GitHub **release** (not pre-release), re-running `ci.yml`, checking the tag is exactly `v<version>` (not SNAPSHOT), signing, then uploading. Secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_KEY_PASSWORD`. Variable `MAVEN_CENTRAL_AUTO_RELEASE=true` releases automatically. A release runs the workflow **as it is at the tagged commit**. Rehearse with Actions > Publish > Run workflow, "publish" unchecked.
- Bump the version in `build.gradle.kts`, the README `pitest(...)` line and `e2e/build.gradle.kts` together, then tag `v<version>`.
- gpg's agent socket path limit (~100 chars) breaks a key home in a deep directory; use `mktemp -d /tmp/gpgt.XXXXXX`.
