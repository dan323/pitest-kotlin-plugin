package io.github.dan323.pitkotlin

import org.pitest.mutationtest.engine.MutationDetails
import org.pitest.mutationtest.engine.gregor.MethodMutatorFactory
import org.pitest.mutationtest.engine.gregor.config.Mutator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElvisDuplicateFilterTest {
    private val elvisMethods = listOf("plain", "fromMap", "earlyReturn", "orThrow", "local", "userNullCheck")

    private fun builtin(name: String) = Mutator.byName(name).toList()

    private fun set(vararg factories: Collection<MethodMutatorFactory>) =
        MutantSet("Elvis", factories.flatMap { it })

    private fun apply(set: MutantSet): List<MutationDetails> =
        KotlinElvisDuplicateFilter().intercept(set.mutants, set.mutater).toList()

    private fun kinds(ms: List<MutationDetails>, method: String) =
        ms.filter { it.method == method }.map { it.mutator.substringAfterLast('.') }.sorted()

    @Test
    fun `PIT's equal else mutant is byte for byte the never default mutant`() {
        val builtinElse = set(builtin("REMOVE_CONDITIONALS_EQUAL_ELSE"))
        val never = set(listOf(KotlinElvisNeverDefaultMutator()))
        for (method in elvisMethods) {
            val a = builtinElse.inMethod(method).single { it.id.firstIndex in never.inMethod(method).map { n -> n.id.firstIndex } }
            val b = never.inMethod(method).single { it.id.firstIndex == a.id.firstIndex }
            assertContentEquals(builtinElse.bytes(a), never.bytes(b), "$method: EQUAL_ELSE vs NEVER")
        }
    }

    @Test
    fun `PIT's equal if mutant is byte for byte the always default mutant`() {
        val builtinIf = set(builtin("REMOVE_CONDITIONALS_EQUAL_IF"))
        val always = set(listOf(KotlinElvisAlwaysDefaultMutator()))
        for (method in elvisMethods) {
            val b = always.inMethod(method).single()
            val a = builtinIf.inMethod(method).single { it.id.firstIndex == b.id.firstIndex }
            assertContentEquals(builtinIf.bytes(a), always.bytes(b), "$method: EQUAL_IF vs ALWAYS")
        }
    }

    @Test
    fun `with both elvis mutators every built in conditional on an elvis jump goes`() {
        val s = set(
            builtin("REMOVE_CONDITIONALS_EQUAL_ELSE"), builtin("REMOVE_CONDITIONALS_EQUAL_IF"), builtin("NEGATE_CONDITIONALS"),
            listOf(KotlinElvisAlwaysDefaultMutator(), KotlinElvisNeverDefaultMutator()),
        )
        val kept = apply(s)
        for (method in elvisMethods) {
            assertEquals(listOf("KotlinElvisAlwaysDefaultMutator", "KotlinElvisNeverDefaultMutator"), kinds(kept, method), method)
        }
        // `chain` has two elvis jumps.
        assertEquals(4, kept.count { it.method == "chain" })
    }

    @Test
    fun `conditionals that are not elvis are untouched`() {
        val s = set(
            builtin("REMOVE_CONDITIONALS_EQUAL_ELSE"), builtin("NEGATE_CONDITIONALS"),
            listOf(KotlinElvisAlwaysDefaultMutator(), KotlinElvisNeverDefaultMutator()),
        )
        val before = s.inMethod("notElvis")
        assertTrue(before.isNotEmpty())
        assertEquals(before, apply(s).filter { it.method == "notElvis" })
    }

    @Test
    fun `with only always default the never equivalent default mutant stays`() {
        val s = set(
            builtin("REMOVE_CONDITIONALS_EQUAL_ELSE"), builtin("REMOVE_CONDITIONALS_EQUAL_IF"), builtin("NEGATE_CONDITIONALS"),
            listOf(KotlinElvisAlwaysDefaultMutator()),
        )
        // EQUAL_IF duplicates ALWAYS and goes; EQUAL_ELSE has no replacement and NEGATE is not fully covered.
        assertEquals(
            listOf("KotlinElvisAlwaysDefaultMutator", "NegateConditionalsMutator", "RemoveConditionalMutator_EQUAL_ELSE"),
            kinds(apply(s), "plain"),
        )
    }

    @Test
    fun `without elvis mutators nothing is filtered`() {
        val s = set(builtin("REMOVE_CONDITIONALS_EQUAL_ELSE"), builtin("NEGATE_CONDITIONALS"))
        assertEquals(s.mutants, apply(s))
    }
}
