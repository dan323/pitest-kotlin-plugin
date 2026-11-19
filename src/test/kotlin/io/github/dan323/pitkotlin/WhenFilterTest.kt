package io.github.dan323.pitkotlin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
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
    fun `an enum when loses nothing, its throw cannot be told from a one-line else`() {
        for (m in listOf("enumExpr", "enumStmt")) {
            assertTrue(dropped(m).isEmpty(), m)
            assertTrue(set.inMethod(m).any { it.mutator.contains("Switch") })
            assertTrue(set.inMethod(m).any { it.mutator.endsWith("ConstructorCallMutator") })
        }
    }

    @Test
    fun `a throw that user code writes is not touched`() {
        val userThrows = listOf("explicitSealedElse", "explicitEnumElse", "oneLineEnumElse", "oneLineSealedElse", "oneLineCaseThrow", "laterSwitchCaseThrow", "madeWithMessage", "madeAndReturned", "thrownWithMessage")
        for (m in userThrows) {
            assertTrue(set.inMethod(m).any { it.mutator.endsWith("ConstructorCallMutator") }, m)
            assertTrue(dropped(m).isEmpty(), "$m: ${names(dropped(m))}")
        }
        assertTrue(set.inMethod("madeAndReturned").any { it.mutator.endsWith("ConstructorCallMutator") })
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

/** Hand-built instruction lists for the shapes a Kotlin source cannot be made to produce. */
class GeneratedThrowTest {
    private val target = LabelNode()
    private val site = LabelNode()

    /** `target: line 5: new E; dup; <init>()V; athrow` followed by `site: line 9: <source>`. */
    private fun insns(source: AbstractInsnNode): List<AbstractInsnNode> {
        val list = InsnList()
        list.add(target)
        list.add(LineNumberNode(5, target))
        list.add(TypeInsnNode(Opcodes.NEW, "kotlin/NoWhenBranchMatchedException"))
        list.add(InsnNode(Opcodes.DUP))
        list.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "kotlin/NoWhenBranchMatchedException", "<init>", "()V", false))
        list.add(InsnNode(Opcodes.ATHROW))
        list.add(site)
        list.add(LineNumberNode(9, site))
        list.add(source)
        return list.toArray().toList()
    }

    private fun confirmed(source: AbstractInsnNode): Boolean = insns(source).let { it.isGeneratedThrow(it.indexOfFirst { n -> n.opcode == Opcodes.NEW }) }

    @Test
    fun `a conditional jump from a later line confirms the throw`() {
        assertTrue(confirmed(JumpInsnNode(Opcodes.IFEQ, target)))
        assertTrue(confirmed(JumpInsnNode(Opcodes.IF_ACMPNE, target)))
    }

    @Test
    fun `an unconditional goto from a later line does not`() {
        assertTrue(!confirmed(JumpInsnNode(Opcodes.GOTO, target)))
    }

    @Test
    fun `a switch on a later line does not, as default or as case`() {
        assertTrue(!confirmed(TableSwitchInsnNode(1, 2, target, site, site)))
        assertTrue(!confirmed(TableSwitchInsnNode(1, 2, site, target, site)))
        assertTrue(!confirmed(LookupSwitchInsnNode(target, intArrayOf(1), arrayOf(site))))
        assertTrue(!confirmed(LookupSwitchInsnNode(site, intArrayOf(1), arrayOf(target))))
    }

    @Test
    fun `no jump at all does not`() {
        assertTrue(!confirmed(InsnNode(Opcodes.NOP)))
    }
}
