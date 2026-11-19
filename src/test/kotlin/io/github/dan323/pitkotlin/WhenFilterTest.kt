package io.github.dan323.pitkotlin

import org.pitest.bytecode.analysis.ClassTree
import org.pitest.mutationtest.engine.MutationDetails
import org.pitest.mutationtest.engine.gregor.config.Mutator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WhenFilterTest {
    private val tree = ClassTree.fromBytes(javaClass.classLoader.getResourceAsStream("fixtures/Whens.class")!!.use { it.readBytes() })

    /** Every PIT mutator, so the `*_IF` conditionals and the constructor-call mutant are generated too. */
    private val set = MutantSet("Whens", Mutator.fromStrings(listOf("ALL")).toList())

    private fun dropped(method: String): List<MutationDetails> {
        val all = set.inMethod(method).filter { it.lineNumber != 0 }
        val kept = KotlinWhenFilter().also { it.begin(tree) }.intercept(all, set.mutater).toSet()
        return all.filter { it !in kept }
    }

    private fun names(ms: List<MutationDetails>) = ms.map { it.mutator.substringAfterLast('.') }.sorted()

    @Test
    fun `a sealed when loses the throw constructor and the never jump mutant of its last check`() {
        for (m in listOf("sealedExpr", "sealedInterface")) {
            assertEquals(listOf("ConstructorCallMutator", "RemoveConditionalMutator_EQUAL_IF"), names(dropped(m)), m)
        }
    }

    @Test
    fun `the dropped never jump mutant is the last check and behaves like the original`() {
        val last = set.inMethod("sealedInterface").filter { it.mutator.endsWith("EQUAL_IF") }.maxBy { it.instructionIndex }
        assertTrue(last in dropped("sealedInterface"))
        val mutated = set.instance(last)
        val original = fixtures.Whens()
        for (t in listOf(fixtures.Word("w"), fixtures.Number(5))) {
            assertEquals(original.sealedInterface(t), mutated.call("sealedInterface", t))
        }
    }

    @Test
    fun `the opposite mutants of the last check are kept and a test can kill them`() {
        val lastJump = set.inMethod("sealedInterface").filter { it.mutator.contains("Conditional") }.maxOf { it.instructionIndex }
        val onLast = set.inMethod("sealedInterface").filter { it.instructionIndex == lastJump }
        assertEquals(listOf("NegateConditionalsMutator", "RemoveConditionalMutator_EQUAL_ELSE", "RemoveConditionalMutator_EQUAL_IF"), names(onLast))
        val kept = onLast - dropped("sealedInterface").toSet()
        assertEquals(listOf("NegateConditionalsMutator", "RemoveConditionalMutator_EQUAL_ELSE"), names(kept))
        for (m in kept) {
            assertNotEquals("5", runCatching { set.instance(m).call("sealedInterface", fixtures.Number(5)) }.getOrNull(), m.mutator)
        }
    }

    @Test
    fun `mutants on earlier checks and in the branches are kept`() {
        val firstJump = set.inMethod("sealedInterface").filter { it.mutator.contains("Conditional") }.minOf { it.instructionIndex }
        assertTrue(dropped("sealedInterface").none { it.instructionIndex == firstJump })
        assertTrue(dropped("sealedExpr").none { it.mutator.endsWith("NonVoidMethodCallMutator") || it.mutator.endsWith("PrimitiveReturnsMutator") })
    }

    @Test
    fun `an enum when only loses the throw constructor, its switch mutants stay`() {
        for (m in listOf("enumExpr", "enumStmt")) {
            assertEquals(listOf("ConstructorCallMutator"), names(dropped(m)), m)
            assertTrue(set.inMethod(m).any { it.mutator.contains("Switch") })
        }
    }

    @Test
    fun `a when with an else has nothing to drop`() {
        for (m in listOf("withElse", "plain")) assertTrue(dropped(m).isEmpty(), m)
    }

    @Test
    fun `default mutators lose nothing on the sealed checks`() {
        val defaults = MutantSet("Whens", Mutator.newDefaults().toList())
        val all = defaults.inMethod("sealedInterface").filter { it.lineNumber != 0 }
        val kept = KotlinWhenFilter().also { it.begin(tree) }.intercept(all, defaults.mutater)
        assertEquals(all.size, kept.size)
    }
}
