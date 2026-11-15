package io.github.dan323.pitkotlin

import org.pitest.mutationtest.engine.MutationDetails
import org.pitest.mutationtest.engine.gregor.MethodMutatorFactory
import org.pitest.mutationtest.engine.gregor.config.Mutator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageFilterTest {
    private val tree = org.pitest.bytecode.analysis.ClassTree.fromBytes(
        javaClass.classLoader.getResourceAsStream("fixtures/Messages.class")!!.use { it.readBytes() },
    )

    private fun set() = MutantSet(
        "Messages",
        listOf<MethodMutatorFactory>(
            KotlinElvisAlwaysDefaultMutator(), KotlinElvisNeverDefaultMutator(), KotlinSafeCallNeverNullMutator(),
        ) + Mutator.byName("REMOVE_CONDITIONALS_EQUAL_ELSE") + Mutator.byName("NEGATE_CONDITIONALS"),
    )

    private fun apply(s: MutantSet): List<MutationDetails> =
        KotlinMessageFilter().also { it.begin(tree) }.intercept(s.mutants, s.mutater).toList()

    private fun names(ms: List<MutationDetails>, method: String) =
        ms.filter { it.method == method }.map { it.mutator.substringAfterLast('.') }.sorted()

    @Test
    fun `message elvis loses the never default mutants and keeps always default and negate`() {
        val kept = apply(set())
        for (m in listOf("elvis", "localized")) {
            assertEquals(listOf("KotlinElvisAlwaysDefaultMutator", "NegateConditionalsMutator"), names(kept, m), m)
        }
    }

    @Test
    fun `message safe call loses the never null mutant but keeps PIT's always null mutant`() {
        val s = set()
        assertTrue("KotlinSafeCallNeverNullMutator" in names(s.mutants, "safeCall"))
        val kept = apply(s)
        assertTrue("KotlinSafeCallNeverNullMutator" !in names(kept, "safeCall"))
        assertTrue("RemoveConditionalMutator_EQUAL_ELSE" in names(kept, "safeCall"))
    }

    @Test
    fun `other nullable values are untouched`() {
        val s = set()
        for (m in listOf("otherElvis", "otherSafeCall")) {
            assertEquals(names(s.mutants, m), names(apply(s), m), m)
        }
    }

    @Test
    fun `an explicit null check on the message is a plain conditional and stays`() {
        val s = set()
        assertEquals(names(s.mutants, "explicitCheck"), names(apply(s), "explicitCheck"))
    }
}
