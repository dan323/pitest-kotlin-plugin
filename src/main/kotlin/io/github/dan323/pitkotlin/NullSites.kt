package io.github.dan323.pitkotlin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.pitest.mutationtest.engine.MutationDetails

/**
 * A `dup; ifnonnull L` (elvis) or `dup; ifnull L` (safe call) jump and the mutants that make the *non-null*
 * path run regardless of the value ("never null" side):
 *
 *  - on `ifnonnull`: `KOTLIN_ELVIS_NEVER_DEFAULT` and PIT's `REMOVE_CONDITIONALS_EQUAL_ELSE` (always jump),
 *  - on `ifnull`: `KOTLIN_SAFECALL_NEVER_NULL` and PIT's `REMOVE_CONDITIONALS_EQUAL_IF` (never jump).
 *
 * When the value is known not to be null these mutants cannot change behaviour. The other side
 * (`ALWAYS_DEFAULT`, "always null", `NEGATE_CONDITIONALS`) is never covered here.
 */
internal object NullSites {
    /** Index of the `dup` in front of the jump this "never null" mutant sits on, or null if it is some other mutant. */
    fun neverNullDup(mutation: MutationDetails, insns: List<AbstractInsnNode>): Int? {
        val m = mutation.mutator
        val opcode = when {
            m == KotlinElvisNeverDefaultMutator::class.java.name || m.endsWith("RemoveConditionalMutator_EQUAL_ELSE") -> Opcodes.IFNONNULL
            m == KotlinSafeCallNeverNullMutator::class.java.name || m.endsWith("RemoveConditionalMutator_EQUAL_IF") -> Opcodes.IFNULL
            else -> return null
        }
        // KOTLIN_ELVIS_NEVER_DEFAULT only exists on ifnonnull and SAFECALL_NEVER_NULL only on ifnull.
        if (m == KotlinSafeCallNeverNullMutator::class.java.name && opcode != Opcodes.IFNULL) return null
        val jump = insns.getOrNull(mutation.instructionIndex) ?: return null
        if (jump.opcode != opcode) return null
        val dup = insns.realBefore(mutation.instructionIndex) ?: return null
        if (dup.opcode != Opcodes.DUP) return null
        return insns.indexOf(dup)
    }
}
