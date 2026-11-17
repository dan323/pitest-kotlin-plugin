package io.github.dan323.pitkotlin

import org.pitest.mutationtest.engine.gregor.MethodInfo
import org.pitest.mutationtest.engine.gregor.MethodMutatorFactory
import org.pitest.mutationtest.engine.gregor.MutationContext
import org.pitest.reloc.asm.MethodVisitor
import org.pitest.reloc.asm.Opcodes

/**
 * Replaces a call to a Kotlin stdlib function with its opposite: `first` becomes `last`, `take` becomes
 * `drop`, `startsWith` becomes `endsWith`, `coerceAtLeast` becomes `coerceAtMost`, and so on.
 *
 * PIT has nothing like it (it can remove a call or replace it with its argument, never swap it). A swap is
 * only offered when both functions have the identical descriptor, so the mutated class always verifies.
 * Only non-inline functions can be swapped, because inline ones leave no call behind.
 *
 * The call sites reference the stdlib facade classes (`kotlin/collections/CollectionsKt`), not the
 * `CollectionsKt___CollectionsKt` file classes that declare the code; that is what the table keys on.
 * `StdlibSwapsTest` checks every entry against the stdlib on the classpath.
 */
class KotlinStdlibSwapMutator : MethodMutatorFactory {
    override fun create(context: MutationContext, info: MethodInfo, next: MethodVisitor): MethodVisitor =
        StdlibSwapVisitor(this, context, next)

    override fun getGloballyUniqueId(): String = javaClass.name

    override fun getName(): String = "KOTLIN_STDLIB_SWAP"
}

internal data class StdlibCall(val owner: String, val name: String, val descriptor: String)

internal object StdlibSwaps {
    private const val COLLECTIONS = "kotlin/collections/CollectionsKt"
    private const val SEQUENCES = "kotlin/sequences/SequencesKt"
    private const val RANGES = "kotlin/ranges/RangesKt"
    private const val STRINGS = "kotlin/text/StringsKt"
    private const val MATH = "java/lang/Math"

    private val pairs = mutableListOf<Pair<StdlibCall, StdlibCall>>()

    private fun swap(owner: String, a: String, b: String, vararg descriptors: String) {
        for (d in descriptors) pairs += StdlibCall(owner, a, d) to StdlibCall(owner, b, d)
    }

    init {
        val iterableObj = "(Ljava/lang/Iterable;)Ljava/lang/Object;"
        val listObj = "(Ljava/util/List;)Ljava/lang/Object;"
        swap(COLLECTIONS, "first", "last", listObj, iterableObj)
        swap(COLLECTIONS, "firstOrNull", "lastOrNull", listObj, iterableObj)
        swap(COLLECTIONS, "take", "drop", "(Ljava/lang/Iterable;I)Ljava/util/List;")
        swap(COLLECTIONS, "takeLast", "dropLast", "(Ljava/util/List;I)Ljava/util/List;")
        swap(
            COLLECTIONS, "maxOrNull", "minOrNull",
            "(Ljava/lang/Iterable;)Ljava/lang/Comparable;", "(Ljava/lang/Iterable;)Ljava/lang/Double;", "(Ljava/lang/Iterable;)Ljava/lang/Float;",
        )
        swap(COLLECTIONS, "sorted", "sortedDescending", "(Ljava/lang/Iterable;)Ljava/util/List;")

        swap(SEQUENCES, "first", "last", "(Lkotlin/sequences/Sequence;)Ljava/lang/Object;")
        swap(SEQUENCES, "take", "drop", "(Lkotlin/sequences/Sequence;I)Lkotlin/sequences/Sequence;")

        swap(RANGES, "coerceAtLeast", "coerceAtMost", "(II)I", "(JJ)J", "(FF)F", "(DD)D", "(Ljava/lang/Comparable;Ljava/lang/Comparable;)Ljava/lang/Comparable;")

        swap(STRINGS, "startsWith", "endsWith", "(Ljava/lang/String;Ljava/lang/String;Z)Z")
        swap(STRINGS, "startsWith\$default", "endsWith\$default", "(Ljava/lang/String;Ljava/lang/String;ZILjava/lang/Object;)Z")
        swap(STRINGS, "take", "drop", "(Ljava/lang/String;I)Ljava/lang/String;")
        swap(STRINGS, "takeLast", "dropLast", "(Ljava/lang/String;I)Ljava/lang/String;")
        swap(STRINGS, "first", "last", "(Ljava/lang/CharSequence;)C")
        swap(STRINGS, "trimStart", "trimEnd", "(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;")
        for (target in listOf("Ljava/lang/String;", "C")) {
            val d = "(Ljava/lang/String;${target}Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/String;"
            swap(STRINGS, "substringBefore\$default", "substringAfter\$default", d)
            swap(STRINGS, "substringBeforeLast\$default", "substringAfterLast\$default", d)
        }
        for (target in listOf("Ljava/lang/String;", "C")) {
            swap(STRINGS, "substringBefore", "substringAfter", "(Ljava/lang/String;${target}Ljava/lang/String;)Ljava/lang/String;")
            swap(STRINGS, "substringBeforeLast", "substringAfterLast", "(Ljava/lang/String;${target}Ljava/lang/String;)Ljava/lang/String;")
        }
        swap(STRINGS, "removePrefix", "removeSuffix", "(Ljava/lang/String;Ljava/lang/CharSequence;)Ljava/lang/String;", "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/CharSequence;")
        swap(STRINGS, "trimStart", "trimEnd", "(Ljava/lang/String;[C)Ljava/lang/String;", "(Ljava/lang/CharSequence;[C)Ljava/lang/CharSequence;")
        swap(STRINGS, "startsWith\$default", "endsWith\$default", "(Ljava/lang/CharSequence;CZILjava/lang/Object;)Z")
        swap(STRINGS, "startsWith", "endsWith", "(Ljava/lang/CharSequence;CZ)Z", "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;Z)Z")
        swap(STRINGS, "indexOf\$default", "lastIndexOf\$default", "(Ljava/lang/CharSequence;CIZILjava/lang/Object;)I")
        swap(STRINGS, "indexOf\$default", "lastIndexOf\$default", "(Ljava/lang/CharSequence;Ljava/lang/String;IZILjava/lang/Object;)I")

        for (d in listOf("(II)I", "(JJ)J", "(FF)F", "(DD)D")) swap(MATH, "max", "min", d)
    }

    /** Every swap in both directions; each call has exactly one partner so mutant identifiers stay unique. */
    val partners: Map<StdlibCall, StdlibCall> =
        pairs.flatMap { (a, b) -> listOf(a to b, b to a) }.also { all ->
            val duplicates = all.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
            check(duplicates.isEmpty()) { "calls with several partners: $duplicates" }
        }.toMap()
}

private class StdlibSwapVisitor(
    private val factory: MethodMutatorFactory,
    private val context: MutationContext,
    next: MethodVisitor,
) : MethodVisitor(Opcodes.ASM9, next) {
    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
        val partner = if (opcode == Opcodes.INVOKESTATIC) StdlibSwaps.partners[StdlibCall(owner, name, descriptor)] else null
        if (partner != null) {
            val id = context.registerMutation(factory, "swapped $owner::$name for ${partner.name}")
            if (context.shouldMutate(id)) {
                mv.visitMethodInsn(opcode, partner.owner, partner.name, partner.descriptor, isInterface)
                return
            }
        }
        mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
}
