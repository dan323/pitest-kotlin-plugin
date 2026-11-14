package io.github.dan323.pitkotlin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.pitest.mutationtest.engine.MutationDetails

private const val RESULT_KT = "kotlin/ResultKt"
private const val DEBUG_PROBES = "kotlin/coroutines/jvm/internal/DebugProbesKt"
private const val SUSPENDED_GETTER = "getCOROUTINE_SUSPENDED"

/**
 * Drops mutants on the plumbing of suspend-function state machines:
 *  - `ResultKt.throwOnFailure` and the debug probe call,
 *  - comparisons against `COROUTINE_SUSPENDED`,
 *  - `return COROUTINE_SUSPENDED`.
 *
 * Mutants on the suspend function's own logic are untouched.
 */
class KotlinCoroutinesFilter : KotlinMutationFilter() {
    override fun shouldDrop(mutation: MutationDetails): Boolean {
        val insns = methodOf(mutation)?.instructions() ?: return false
        val index = mutation.instructionIndex
        val insn = insns.getOrNull(index) ?: return false

        return when {
            insn.isStaticCall(RESULT_KT, "throwOnFailure") -> true
            insn.isStaticCall(DEBUG_PROBES, "probeCoroutineSuspended") -> true
            insn.opcode == Opcodes.IF_ACMPEQ || insn.opcode == Opcodes.IF_ACMPNE ||
                insn.opcode == Opcodes.ARETURN -> isSuspendedMarker(insns, insns.realBefore(index))
            else -> false
        }
    }

    /** The marker is either a fresh call to the getter, or a local that was assigned from it. */
    private fun isSuspendedMarker(insns: List<AbstractInsnNode>, operand: AbstractInsnNode?): Boolean =
        when {
            operand == null -> false
            operand.isSuspendedGetter() -> true
            operand is VarInsnNode && operand.opcode == Opcodes.ALOAD -> operand.`var` in suspendedLocals(insns)
            else -> false
        }

    private fun suspendedLocals(insns: List<AbstractInsnNode>): Set<Int> =
        insns.indices.mapNotNull { i ->
            val store = insns.realAfter(i)
            if (insns[i].isSuspendedGetter() && store is VarInsnNode && store.opcode == Opcodes.ASTORE) store.`var` else null
        }.toSet()

    private fun AbstractInsnNode.isSuspendedGetter(): Boolean =
        this is org.objectweb.asm.tree.MethodInsnNode && opcode == Opcodes.INVOKESTATIC && name == SUSPENDED_GETTER
}

class KotlinCoroutinesFilterFactory : KotlinFilterFactory(
    "FKTCORO",
    "Filters mutations on compiler-generated coroutine state machine plumbing",
    { KotlinCoroutinesFilter() },
)
