package io.github.dan323.pitkotlin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodInsnNode
import org.pitest.mutationtest.engine.MutationDetails

/**
 * Drops the "this value is never null" mutants on `Throwable.message`.
 *
 * `e.message ?: "default"` and `e.message?.f()` follow a null check on an exception message. The
 * exceptions a program catches almost always carry a message, so the mutants that assume it is never
 * null (`KOTLIN_ELVIS_NEVER_DEFAULT`, `KOTLIN_SAFECALL_NEVER_NULL`, and PIT's `REMOVE_CONDITIONALS_EQUAL_ELSE`,
 * which is the same mutant) can only be killed by a test that builds an exception *without* a message.
 *
 * Only the never-null side goes. `KOTLIN_ELVIS_ALWAYS_DEFAULT` stays: it is killed by any test that
 * asserts the message of a failure.
 *
 * This is a heuristic: a real `NullPointerException()` has no message, and a test can say so. It is off by
 * default; enable it with `+FKTMESSAGE`.
 */
class KotlinMessageFilter : KotlinMutationFilter() {
    override fun shouldDrop(mutation: MutationDetails): Boolean {
        val kind = when {
            mutation.mutator == KotlinElvisNeverDefaultMutator::class.java.name -> Jump.ELVIS
            mutation.mutator.endsWith("RemoveConditionalMutator_EQUAL_ELSE") -> Jump.ELVIS
            mutation.mutator == KotlinSafeCallNeverNullMutator::class.java.name -> Jump.SAFE_CALL
            else -> return false
        }
        val insns = methodOf(mutation)?.instructions() ?: return false
        val jump = insns.getOrNull(mutation.instructionIndex) ?: return false
        val expected = if (kind == Jump.ELVIS) Opcodes.IFNONNULL else Opcodes.IFNULL
        if (jump.opcode != expected) return false
        val dup = insns.realBefore(mutation.instructionIndex) ?: return false
        if (dup.opcode != Opcodes.DUP) return false
        val dupIndex = insns.indexOf(dup)
        val call = insns.realBefore(dupIndex) as? MethodInsnNode ?: return false
        return call.name in MESSAGE_GETTERS && call.desc == "()Ljava/lang/String;" && call.opcode != Opcodes.INVOKESTATIC
    }

    private enum class Jump { ELVIS, SAFE_CALL }

    private companion object {
        val MESSAGE_GETTERS = setOf("getMessage", "getLocalizedMessage")
    }
}

/** Off by default: unlike the other filters it can hide a gap a test could close (see [KotlinMessageFilter]). */
class KotlinMessageFilterFactory : KotlinFilterFactory(
    "FKTMESSAGE",
    "Filters 'message is never null' mutants on Throwable.message elvis and safe calls (opt-in heuristic)",
    { KotlinMessageFilter() },
    onByDefault = false,
)
