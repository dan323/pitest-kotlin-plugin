package io.github.dan323.pitkotlin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.pitest.mutationtest.engine.MutationDetails

/**
 * Drops mutants that only touch the unreachable `else` the compiler adds to an exhaustive `when` on an
 * enum or a sealed type: `else -> throw NoWhenBranchMatchedException()`.
 *
 * Two shapes are dropped:
 *
 *  - the constructor call (or `Intrinsics.throwNoWhenBranchMatchedException()`) that builds the throw;
 *  - PIT's "replaced the check with true" mutants (`REMOVE_CONDITIONALS_*_IF`, the *never jump* side) on a
 *    conditional jump whose target is that throw. That jump is the last branch's type check; making it
 *    always succeed changes nothing, because the value has already matched no other branch.
 *
 * Deliberately kept: the opposite mutants (`NEGATE_CONDITIONALS`, `REMOVE_CONDITIONALS_*_ELSE`) on the same
 * jump. They send a value that does match the last branch to the throw, so any test that reaches that branch
 * kills them, and a survivor means that branch is untested.
 *
 * Only a bytecode pattern, no test results. With PIT's default mutators the first shape is the only one
 * generated (`REMOVE_CONDITIONALS_*_IF` is in `STRONGER`/`ALL`).
 */
class KotlinWhenFilter : KotlinMutationFilter() {
    override fun shouldDrop(mutation: MutationDetails): Boolean {
        val insns = methodOf(mutation)?.instructions() ?: return false
        val node = insns.getOrNull(mutation.instructionIndex) ?: return false
        return when {
            node.isMatchedThrow() -> true
            node is JumpInsnNode && mutation.isNeverJump() -> insns.realAfter(insns.indexOf(node.label))?.isMatchedThrowStart() == true
            else -> false
        }
    }

    private fun MutationDetails.isNeverJump(): Boolean =
        mutator.endsWith("RemoveConditionalMutator_EQUAL_IF") || mutator.endsWith("RemoveConditionalMutator_ORDER_IF")

    /** The call that finishes building the exception, or the Kotlin 1.x helper that throws it. */
    private fun AbstractInsnNode.isMatchedThrow(): Boolean =
        this is MethodInsnNode &&
            ((opcode == Opcodes.INVOKESPECIAL && owner == EXCEPTION && name == "<init>") ||
                (opcode == Opcodes.INVOKESTATIC && owner == INTRINSICS && name == THROW_HELPER))

    /** First instruction of the throw block: `new NoWhenBranchMatchedException` or the helper call. */
    private fun AbstractInsnNode.isMatchedThrowStart(): Boolean =
        (this is TypeInsnNode && opcode == Opcodes.NEW && desc == EXCEPTION) ||
            (this is MethodInsnNode && opcode == Opcodes.INVOKESTATIC && owner == INTRINSICS && name == THROW_HELPER)

    private companion object {
        const val EXCEPTION = "kotlin/NoWhenBranchMatchedException"
        const val INTRINSICS = "kotlin/jvm/internal/Intrinsics"
        const val THROW_HELPER = "throwNoWhenBranchMatchedException"
    }
}

class KotlinWhenFilterFactory : KotlinFilterFactory(
    "FKTWHEN",
    "Filters mutants on the unreachable else of an exhaustive when on an enum or sealed type",
    { KotlinWhenFilter() },
)
