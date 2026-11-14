package io.github.dan323.pitkotlin

import org.pitest.bytecode.analysis.ClassTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeCallMutatorTest {
    private val safe = { MutantSet("SafeCalls", KotlinSafeCallNeverNullMutator()) }

    @Test
    fun `one mutant per safe call`() {
        val counts = safe().mutants.groupingBy { it.method }.eachCount()
        assertEquals(1, counts["value"], counts.toString())
        assertEquals(1, counts["length"], counts.toString())
        assertEquals(2, counts["chained"], counts.toString())
        assertEquals(1, counts["statement"], counts.toString())
        assertEquals(1, counts["letResult"], counts.toString())
    }

    @Test
    fun `a null receiver now reaches the call and fails`() {
        val set = safe()
        assertNull(fixtures.SafeCalls().value(null))

        val mutated = set.instance(set.inMethod("value").single())
        assertEquals("x", mutated.call("value", "  x ")) // non-null receivers are unaffected
        assertFailsWith<NullPointerException> { mutated.call("value", null) }
    }

    @Test
    fun `each link of a chained safe call is mutated independently`() {
        val set = safe()
        val (outer, inner) = set.inMethod("chained")
        // Outer link never short-circuits: a null input reaches `trim` and throws.
        assertFailsWith<NullPointerException> { set.instance(outer).call("chained", null) }
        // Inner link: `trim` never returns null, so this mutant is unobservable for any input.
        assertEquals(2, set.instance(inner).call("chained", " xy "))
        assertNull(set.instance(inner).call("chained", null))
    }

    @Test
    fun `statement form and let are covered`() {
        val set = safe()
        val stmt = set.instance(set.inMethod("statement").single())
        stmt.call("statement", "abc")
        assertFailsWith<NullPointerException> { stmt.call("statement", null) }
        // `it + "!"` does not throw on null, so the mutant is observable through its different result.
        assertNull(fixtures.SafeCalls().letResult(null))
        assertEquals("null!", set.instance(set.inMethod("letResult").single()).call("letResult", null))
    }

    @Test
    fun `elvis and plain null checks are not matched`() {
        // Elvis uses ifnonnull; this mutator only matches the ifnull of a safe call.
        val elvisOnly = MutantSet("Elvis", KotlinSafeCallNeverNullMutator()).mutants
        assertTrue(elvisOnly.none { it.method in setOf("plain", "fromMap", "earlyReturn", "orThrow", "local", "chain", "userNullCheck") }, elvisOnly.toString())
    }

    @Test
    fun `lateinit guard matches the shape but the intrinsics filter removes it`() {
        val set = MutantSet("NullChecks", KotlinSafeCallNeverNullMutator())
        assertEquals(1, set.mutants.size, "lateinit getter has the dup ifnull shape")
        val tree = ClassTree.fromBytes(javaClass.classLoader.getResourceAsStream("fixtures/NullChecks.class")!!.use { it.readBytes() })
        val kept = KotlinIntrinsicsFilter().apply { begin(tree) }.intercept(set.mutants, set.mutater)
        assertTrue(kept.isEmpty(), "FKTINTRINSICS must drop the lateinit mutant")
    }

    @Test
    fun `dedup drops PIT equal if on a safe call but keeps equal else and negate`() {
        val s = MutantSet(
            "SafeCalls",
            org.pitest.mutationtest.engine.gregor.config.Mutator.byName("REMOVE_CONDITIONALS_EQUAL_ELSE").toList() +
                org.pitest.mutationtest.engine.gregor.config.Mutator.byName("REMOVE_CONDITIONALS_EQUAL_IF").toList() +
                org.pitest.mutationtest.engine.gregor.config.Mutator.byName("NEGATE_CONDITIONALS").toList() +
                KotlinSafeCallNeverNullMutator(),
        )
        val kept = KotlinElvisDuplicateFilter().intercept(s.mutants, s.mutater).filter { it.method == "value" }
        assertEquals(
            listOf("KotlinSafeCallNeverNullMutator", "NegateConditionalsMutator", "RemoveConditionalMutator_EQUAL_ELSE"),
            kept.map { it.mutator.substringAfterLast('.') }.sorted(),
        )
    }

    @Test
    fun `PIT equal if mutant is byte for byte the safe call mutant`() {
        val builtin = MutantSet("SafeCalls", org.pitest.mutationtest.engine.gregor.config.Mutator.byName("REMOVE_CONDITIONALS_EQUAL_IF").toList())
        val mine = safe()
        for (method in listOf("value", "length", "statement", "letResult")) {
            val b = mine.inMethod(method).single()
            val a = builtin.inMethod(method).single { it.id.firstIndex == b.id.firstIndex }
            kotlin.test.assertContentEquals(builtin.bytes(a), mine.bytes(b), method)
        }
    }
}
