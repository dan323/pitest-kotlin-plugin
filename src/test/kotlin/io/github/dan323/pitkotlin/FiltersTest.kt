package io.github.dan323.pitkotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FiltersTest {
    private fun descriptions(mutants: List<org.pitest.mutationtest.engine.MutationDetails>) =
        mutants.map { it.description }

    @Test
    fun `intrinsics filter drops platform type checks but keeps return mutants`() {
        val r = FilterTestSupport.run("Inlines", KotlinIntrinsicsFilter())
        assertEquals(
            listOf("removed call to kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue"),
            descriptions(r.droppedIn("platform")),
        )
        assertTrue(r.keptIn("platform").isNotEmpty())
    }

    @Test
    fun `intrinsics filter drops lateinit guard and dead return but keeps live return`() {
        val r = FilterTestSupport.run("NullChecks", KotlinIntrinsicsFilter())
        val dropped = r.droppedIn("getLate")
        assertEquals(3, dropped.size, descriptions(dropped).toString())
        assertTrue(dropped.any { it.description == "negated conditional" })
        assertTrue(dropped.any { it.description.contains("throwUninitializedPropertyAccessException") })
        assertEquals(1, r.keptIn("getLate").size) // the real return
    }

    @Test
    fun `intrinsics filter keeps user written double bang`() {
        val r = FilterTestSupport.run("NullChecks", KotlinIntrinsicsFilter())
        assertTrue(r.droppedIn("bang").isEmpty())
        assertTrue(r.keptIn("bang").any { it.description.contains("Intrinsics::checkNotNull") })
    }

    @Test
    fun `intrinsics filter drops InlineMarker calls around suspend calls in inline functions`() {
        val r = FilterTestSupport.run("FixturesKt", KotlinIntrinsicsFilter())
        assertTrue(r.dropped.isNotEmpty(), "expected InlineMarker mutants to exist in the fixture")
        assertTrue(r.dropped.all { it.description.contains("InlineMarker") }, descriptions(r.dropped).toString())
        assertTrue(r.kept.none { it.description.contains("InlineMarker") })
    }

    @Test
    fun `forInline filter drops the never executed copy and nothing else`() {
        val r = FilterTestSupport.run("FixturesKt", KotlinForInlineFilter())
        assertTrue(r.dropped.isNotEmpty())
        assertTrue(r.dropped.all { it.method.endsWith("\$\$forInline") })
        assertTrue(r.kept.none { it.method.endsWith("\$\$forInline") })
        assertTrue(r.kept.any { it.method == "forwardInline" })
    }

    @Test
    fun `inlined code filter drops stdlib inline bodies but keeps lambdas and own inline code`() {
        val r = FilterTestSupport.run("Inlines", KotlinInlinedCodeFilter())
        assertTrue(r.droppedIn("mapped").isNotEmpty())

        // Lambda bodies (`it + 1`, `it > 2`) stay.
        assertTrue(r.keptIn("mapped").any { it.description.contains("addition") })
        assertTrue(r.keptIn("mapped").any { it.description.contains("conditional") })
        // `twice` is the project's own inline function: its inlined copy is kept by default.
        assertTrue(r.droppedIn("own").isEmpty())
        assertTrue(r.keptIn("own").any { it.description.contains("addition") })
    }

    @Test
    fun `inlined code filter with scope all also drops the projects own inline code`() {
        val r = FilterTestSupport.run("Inlines", KotlinInlinedCodeFilter(dropAll = true))
        assertTrue(r.droppedIn("own").any { it.description.contains("addition") })
        assertTrue(r.keptIn("own").any { it.description.contains("replaced int return") })
        assertTrue(r.dropped.none { it.method == "platform" })
    }

    @Test
    fun `inlined code filter honours extra library prefixes`() {
        val r = FilterTestSupport.run("Inlines", KotlinInlinedCodeFilter(libraryPrefixes = listOf("fixtures/")))
        assertTrue(r.droppedIn("own").any { it.description.contains("addition") })
        assertTrue(r.droppedIn("mapped").isEmpty())
    }

    // `find` is left out: it is @InlineOnly and delegates to `firstOrNull`, so the compiler gives its inlined body
    // the call site's line instead of synthetic ones. The SMAP cannot see it, so it is neither dropped nor relabelled.
    private val predicateMethods = listOf("filter", "filterNot", "any", "all", "none", "count", "first", "takeWhile", "partition")

    @Test
    fun `inlined code filter keeps the jump that consumes an inlined predicate and reports it on the lambda line`() {
        val r = FilterTestSupport.run("Predicates", KotlinInlinedCodeFilter())
        for (method in predicateMethods) {
            val kept = r.keptIn(method).filter { it.description.contains("predicate:") }
            assertEquals(1, kept.size, "$method: ${descriptions(r.keptIn(method))}")
            assertTrue(kept.single().description.startsWith("$method{} predicate: "), kept.single().description)
            // The lambda body (`it > 2`) lives on the same line as the call; the jump's own line is synthetic.
            val lambdaLine = r.keptIn(method).first { !it.description.contains("predicate:") }.lineNumber
            assertEquals(lambdaLine, kept.single().lineNumber, method)
        }
        // The rest of the inlined loop (hasNext, add, counters) is still dropped.
        assertTrue(r.dropped.isNotEmpty())
        assertTrue(r.dropped.none { it.description.contains("predicate:") })
    }

    @Test
    fun `inline only functions are invisible to the SMAP and keep all their mutants`() {
        val r = FilterTestSupport.run("Predicates", KotlinInlinedCodeFilter())
        assertTrue(r.droppedIn("find").isEmpty(), "known limitation: find's loop code is attributed to the call line")
        assertTrue(r.keptIn("find").none { it.description.contains("predicate:") })
    }

    @Test
    fun `predicates drop restores the old behavior`() {
        val r = FilterTestSupport.run("Predicates", KotlinInlinedCodeFilter(keepPredicates = false))
        assertTrue(r.kept.none { it.description.contains("predicate:") })
        for (method in predicateMethods) assertTrue(r.droppedIn(method).isNotEmpty(), method)
    }

    @Test
    fun `a relocated predicate mutant keeps its identity so PIT can still run it`() {
        val kept = FilterTestSupport.run("Predicates", KotlinInlinedCodeFilter()).keptIn("filter").single { it.description.contains("predicate:") }
        val original = FilterTestSupport.run("Predicates", object : org.pitest.mutationtest.build.MutationInterceptor {
            override fun type() = org.pitest.mutationtest.build.InterceptorType.FILTER
            override fun begin(clazz: org.pitest.bytecode.analysis.ClassTree) {}
            override fun intercept(mutations: Collection<org.pitest.mutationtest.engine.MutationDetails>, m: org.pitest.mutationtest.engine.Mutater) = mutations
            override fun end() {}
        }).kept.single { it.id == kept.id }
        assertTrue(original.lineNumber > kept.lineNumber, "synthetic line ${original.lineNumber} should be past the lambda line ${kept.lineNumber}")
        assertEquals(original.blocks, kept.blocks)
    }

    @Test
    fun `coroutines filter drops plumbing but keeps suspend function logic`() {
        val r = FilterTestSupport.run("Coroutines", KotlinCoroutinesFilter())
        val droppedKinds = r.dropped.map { it.description }
        assertTrue(droppedKinds.any { it.contains("throwOnFailure") })
        assertTrue(droppedKinds.any { it.contains("probeCoroutineSuspended") })
        assertTrue(r.dropped.any { it.description == "negated conditional" })

        val keptKinds = r.kept.map { it.description }
        assertTrue(keptKinds.any { it.contains("resumeWith") })
        assertTrue(keptKinds.any { it.contains("changed conditional boundary") })
        assertTrue(keptKinds.any { it.contains("addition") })
    }

    @Test
    fun `unit filter drops Unit return in lambda but keeps the println call`() {
        val r = FilterTestSupport.run("Lambdas", KotlinUnitReturnFilter())
        assertEquals(1, r.dropped.size, descriptions(r.dropped).toString())
        assertEquals("unitLambda\$lambda\$0", r.dropped.single().method)
        assertTrue(r.dropped.single().description.contains("replaced return value with null"))
        assertTrue(r.kept.any { it.description.contains("println") })
    }

    @Test
    fun `coroutines filter keeps the return of a suspend lambda result`() {
        // That areturn is also reached on immediate completion, so nulling it is a real mutant.
        val r = FilterTestSupport.run("Lambdas\$tailSuspend\$1", KotlinCoroutinesFilter())
        assertTrue(r.kept.any { it.method == "invokeSuspend" && it.description.contains("replaced return value with null") })
    }

    @Test
    fun `smap parser reports inlined ranges with their origin class`() {
        val smap = "SMAP\nF.kt\nKotlin\n*S Kotlin\n*F\n+ 1 F.kt\nfixtures/F\n+ 2 _Collections.kt\nkotlin/collections/CollectionsKt\n+ 3 F.kt\nfixtures/FKt\n*L\n1#1,53:1\n1745#2:54\n1820#2,3:55\n44#3:61\n*E\n"
        assertEquals(
            listOf(
                InlinedRange(54..54, "kotlin/collections/CollectionsKt"),
                InlinedRange(55..57, "kotlin/collections/CollectionsKt"),
                InlinedRange(61..61, "fixtures/FKt"),
            ),
            Smap.inlinedRanges(smap),
        )
        assertEquals(emptyList(), Smap.inlinedRanges(null))
    }
}
