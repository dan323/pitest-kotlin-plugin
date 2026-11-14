package io.github.dan323.pitkotlin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.FieldInsnNode
import org.pitest.mutationtest.engine.MutationDetails

/**
 * Drops "replaced return value with null" style mutants on `return Unit.INSTANCE`.
 *
 * Kotlin compiles `Unit` functions to `void`, so `getstatic kotlin/Unit.INSTANCE; areturn` only
 * shows up where `Unit` is a real value: lambdas such as `() -> Unit`, generic overrides and
 * suspend lambdas. Callers practically never look at that value, so the mutant is noise.
 */
class KotlinUnitReturnFilter : KotlinMutationFilter() {
    override fun shouldDrop(mutation: MutationDetails): Boolean {
        val insns = methodOf(mutation)?.instructions() ?: return false
        val index = mutation.instructionIndex
        if (insns.getOrNull(index)?.opcode != Opcodes.ARETURN) return false
        val before = insns.realBefore(index)
        return before is FieldInsnNode && before.opcode == Opcodes.GETSTATIC &&
            before.owner == "kotlin/Unit" && before.name == "INSTANCE"
    }
}

class KotlinUnitReturnFilterFactory : KotlinFilterFactory(
    "FKTUNIT",
    "Filters return-value mutations on lambdas and overrides that return Unit.INSTANCE",
    { KotlinUnitReturnFilter() },
)
