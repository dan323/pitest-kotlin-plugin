package io.github.dan323.pitkotlin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.pitest.mutationtest.engine.MutationDetails

private const val INTRINSICS = "kotlin/jvm/internal/Intrinsics"
private const val INLINE_MARKER = "kotlin/jvm/internal/InlineMarker"
private const val THROW_UNINITIALIZED = "throwUninitializedPropertyAccessException"

private val COMPILER_CHECKS = arrayOf(
    // Null checks the compiler inserts itself. `checkNotNull` is deliberately absent: it is `!!`.
    "checkNotNullExpressionValue",
    "checkExpressionValueIsNotNull",
    "checkNotNullParameter",
    "checkParameterIsNotNull",
    "checkReturnedValueIsNotNull",
    "checkFieldIsNotNull",
    THROW_UNINITIALIZED,
)

/**
 * Drops mutants on null checks that the Kotlin compiler generates:
 *  - `Intrinsics.checkNotNullExpressionValue` etc. (platform types, parameters),
 *  - `InlineMarker.mark` and friends, which the compiler emits around inlined suspend calls,
 *  - the `lateinit` guard: `if (field == null) throwUninitializedPropertyAccessException(...)`
 *    and the dead return after it.
 *
 * User-written `!!` (`Intrinsics.checkNotNull`) is still mutated.
 */
class KotlinIntrinsicsFilter : KotlinMutationFilter() {
    override fun shouldDrop(mutation: MutationDetails): Boolean {
        val insns = methodOf(mutation)?.instructions() ?: return false
        val index = mutation.instructionIndex
        val insn = insns.getOrNull(index) ?: return false

        return when {
            insn.isStaticCall(INTRINSICS, *COMPILER_CHECKS) -> true
            insn.isStaticCall(INLINE_MARKER, "mark", "beforeInlineCall", "afterInlineCall", "finallyStart", "finallyEnd") -> true
            insn.opcode == Opcodes.IFNULL || insn.opcode == Opcodes.IFNONNULL ->
                guardsLateinit(insns, index)
            insn.opcode in Opcodes.IRETURN..Opcodes.RETURN -> followsLateinitThrow(insns, index)
            else -> false
        }
    }

    /** True when either path of this null test (fall-through or jump target) immediately throws the lateinit exception. */
    private fun guardsLateinit(insns: List<AbstractInsnNode>, index: Int): Boolean {
        val target = (insns[index] as JumpInsnNode).label
        return startsWithLateinitThrow(insns, index) || startsWithLateinitThrow(insns, insns.indexOf(target))
    }

    private fun startsWithLateinitThrow(insns: List<AbstractInsnNode>, from: Int): Boolean {
        var i = from
        repeat(LOOKAHEAD) {
            val next = insns.realAfter(i) ?: return false
            if (next.isStaticCall(INTRINSICS, THROW_UNINITIALIZED)) return true
            i = insns.indexOf(next)
        }
        return false
    }

    private fun followsLateinitThrow(insns: List<AbstractInsnNode>, index: Int): Boolean {
        var current: AbstractInsnNode = insns[index]
        repeat(LOOKAHEAD) {
            current = insns.realBefore(insns.indexOf(current)) ?: return false
            if (current.isStaticCall(INTRINSICS, THROW_UNINITIALIZED)) return true
        }
        return false
    }

    private companion object {
        const val LOOKAHEAD = 4
    }
}

class KotlinIntrinsicsFilterFactory : KotlinFilterFactory(
    "FKTINTRINSICS",
    "Filters mutations on compiler-generated Kotlin null checks and lateinit guards",
    { KotlinIntrinsicsFilter() },
)
