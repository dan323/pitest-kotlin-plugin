package io.github.dan323.pitkotlin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.pitest.mutationtest.engine.MutationDetails

/**
 * Drops mutants that only touch the unreachable `else` the compiler adds to an exhaustive `when` on an
 * enum or a sealed type: `else -> throw NoWhenBranchMatchedException()`.
 *
 * Two shapes are dropped:
 *
 *  - the constructor call that builds the throw;
 *  - PIT's "replaced the check with true" mutants (`REMOVE_CONDITIONALS_*_IF`, the *never jump* side) on a
 *    conditional jump whose target is that throw. That jump is the last branch's type check; making it
 *    always succeed changes nothing, because the value has already matched no other branch.
 *
 * Deliberately kept: the opposite mutants (`NEGATE_CONDITIONALS`, `REMOVE_CONDITIONALS_*_ELSE`) on the same
 * jump. They send a value that does match the last branch to the throw, so any test that reaches that branch
 * kills them, and a survivor means that branch is untested.
 *
 * User-written throws (an explicit `else`, `NoWhenBranchMatchedException("msg")`, one that is returned) are kept: see
 * `isGeneratedThrow`. Only a bytecode pattern, no test results. PIT's default mutators generate neither shape
 * (`REMOVE_CONDITIONALS_*_IF` is in `STRONGER`/`ALL`, the constructor-call removal in `ALL`).
 */
class KotlinWhenFilter : KotlinMutationFilter() {
    override fun shouldDrop(mutation: MutationDetails): Boolean {
        val insns = methodOf(mutation)?.instructions() ?: return false
        val index = mutation.instructionIndex
        val node = insns.getOrNull(index) ?: return false
        return when {
            node is MethodInsnNode && node.opcode == Opcodes.INVOKESPECIAL && node.owner == EXCEPTION && node.name == "<init>" -> {
                val dup = insns.realBefore(index)
                val new = dup?.let { insns.realBefore(insns.indexOf(it)) }
                new != null && insns.isGeneratedThrow(insns.indexOf(new))
            }
            node is JumpInsnNode && mutation.isNeverJump() -> {
                val target = insns.realAfter(insns.indexOf(node.label))
                target is TypeInsnNode && insns.isGeneratedThrow(insns.indexOf(target))
            }
            else -> false
        }
    }

    private fun MutationDetails.isNeverJump(): Boolean =
        mutator.endsWith("RemoveConditionalMutator_EQUAL_IF") || mutator.endsWith("RemoveConditionalMutator_ORDER_IF")

    /**
     * Whether the `new NoWhenBranchMatchedException` at [newIndex] is the compiler's fallback and not user code.
     * The fallback is `new; dup; <init>()V; athrow`, only reached by a jump or switch default, and it carries the
     * line of the `when` subject: earlier than the last branch's check that jumps to it, or equal to the line of
     * the enum `tableswitch` whose default it is. A user-written throw (an explicit `else`, an exception that is
     * returned, or one built with a message) fails one of these tests, and anything unconfirmed is kept.
     */
    private fun List<AbstractInsnNode>.isGeneratedThrow(newIndex: Int): Boolean {
        val new = this[newIndex] as? TypeInsnNode ?: return false
        if (new.opcode != Opcodes.NEW || new.desc != EXCEPTION) return false
        val dup = realAfter(newIndex) ?: return false
        val init = realAfter(indexOf(dup)) as? MethodInsnNode ?: return false
        val athrow = realAfter(indexOf(init)) ?: return false
        if (dup.opcode != Opcodes.DUP || init.owner != EXCEPTION || init.name != "<init>" || init.desc != "()V" || athrow.opcode != Opcodes.ATHROW) {
            return false
        }
        val labels = labelsAt(newIndex)
        val throwLine = lineOf(newIndex) ?: return false
        val sources = indices.filter { i -> val n = this[i]; n.jumpsTo(labels) }
        return sources.isNotEmpty() && sources.all { i ->
            val line = lineOf(i) ?: return@all false
            val source = this[i]
            line > throwLine || (line == throwLine && (source is TableSwitchInsnNode || source is LookupSwitchInsnNode))
        }
    }

    private fun AbstractInsnNode.jumpsTo(labels: Set<LabelNode>): Boolean = when (this) {
        is JumpInsnNode -> label in labels
        is TableSwitchInsnNode -> dflt in labels || labels.any { it in this.labels }
        is LookupSwitchInsnNode -> dflt in labels || labels.any { it in this.labels }
        else -> false
    }

    /** Labels (and line/frame markers between them) directly in front of the instruction at [index]. */
    private fun List<AbstractInsnNode>.labelsAt(index: Int): Set<LabelNode> {
        val out = mutableSetOf<LabelNode>()
        var i = index - 1
        while (i >= 0 && this[i].opcode < 0) {
            (this[i] as? LabelNode)?.let(out::add)
            i--
        }
        return out
    }

    /** Source line in force at [index]: the nearest line number at or before it. */
    private fun List<AbstractInsnNode>.lineOf(index: Int): Int? {
        var i = index
        while (i >= 0) {
            (this[i] as? LineNumberNode)?.let { return it.line }
            i--
        }
        return null
    }

    private companion object {
        const val EXCEPTION = "kotlin/NoWhenBranchMatchedException"
    }
}

class KotlinWhenFilterFactory : KotlinFilterFactory(
    "FKTWHEN",
    "Filters mutants on the unreachable else of an exhaustive when on an enum or sealed type",
    { KotlinWhenFilter() },
)
