package io.github.dan323.pitkotlin

import org.pitest.mutationtest.engine.gregor.MethodInfo
import org.pitest.mutationtest.engine.gregor.MethodMutatorFactory
import org.pitest.mutationtest.engine.gregor.MutationContext
import org.pitest.reloc.asm.MethodVisitor
import org.pitest.reloc.asm.Opcodes

/**
 * Removes a call to `distinct()` on an `Iterable` or a `Sequence`: the result keeps its duplicates.
 *
 * PIT's void-method-call removal cannot do this (the call returns a value) and `KOTLIN_STDLIB_SWAP` only swaps a call for
 * its opposite. A test that passes input with duplicates kills it.
 */
class KotlinRemoveDistinctMutator : MethodMutatorFactory {
    override fun create(context: MutationContext, info: MethodInfo, next: MethodVisitor): MethodVisitor =
        RemoveCallVisitor(this, context, next, RemovableCalls.distinct)

    override fun getGloballyUniqueId(): String = javaClass.name

    override fun getName(): String = "KOTLIN_REMOVE_DISTINCT"
}

/**
 * Removes a call to `sorted()`, `sortedDescending()` or `sortedWith(comparator)`: the result keeps the input order.
 * A test that passes unsorted input and checks the order of the result kills it.
 */
class KotlinRemoveSortedMutator : MethodMutatorFactory {
    override fun create(context: MutationContext, info: MethodInfo, next: MethodVisitor): MethodVisitor =
        RemoveCallVisitor(this, context, next, RemovableCalls.sorted)

    override fun getGloballyUniqueId(): String = javaClass.name

    override fun getName(): String = "KOTLIN_REMOVE_SORTED"
}

/**
 * How to remove a call and still leave the stack as the call would have: pop the [pops] extra arguments (the
 * comparator of `sortedWith`), then call `toList()` when the result is a `List` (`Iterable -> List`) or
 * leave the receiver as it is when the result has its own type (`Sequence -> Sequence`).
 */
internal data class Removal(val pops: Int, val toList: Boolean)

internal object RemovableCalls {
    private const val COLLECTIONS = "kotlin/collections/CollectionsKt"
    private const val SEQUENCES = "kotlin/sequences/SequencesKt"
    private const val ITERABLE_LIST = "(Ljava/lang/Iterable;)Ljava/util/List;"
    private const val SEQUENCE_SEQUENCE = "(Lkotlin/sequences/Sequence;)Lkotlin/sequences/Sequence;"

    val distinct: Map<StdlibCall, Removal> = mapOf(
        StdlibCall(COLLECTIONS, "distinct", ITERABLE_LIST) to Removal(0, true),
        StdlibCall(SEQUENCES, "distinct", SEQUENCE_SEQUENCE) to Removal(0, false),
    )

    val sorted: Map<StdlibCall, Removal> = mapOf(
        StdlibCall(COLLECTIONS, "sorted", ITERABLE_LIST) to Removal(0, true),
        StdlibCall(COLLECTIONS, "sortedDescending", ITERABLE_LIST) to Removal(0, true),
        StdlibCall(COLLECTIONS, "sortedWith", "(Ljava/lang/Iterable;Ljava/util/Comparator;)Ljava/util/List;") to Removal(1, true),
        StdlibCall(SEQUENCES, "sorted", SEQUENCE_SEQUENCE) to Removal(0, false),
        StdlibCall(SEQUENCES, "sortedDescending", SEQUENCE_SEQUENCE) to Removal(0, false),
        StdlibCall(SEQUENCES, "sortedWith", "(Lkotlin/sequences/Sequence;Ljava/util/Comparator;)Lkotlin/sequences/Sequence;") to Removal(1, false),
    )
}

private class RemoveCallVisitor(
    private val factory: MethodMutatorFactory,
    private val context: MutationContext,
    next: MethodVisitor,
    private val calls: Map<StdlibCall, Removal>,
) : MethodVisitor(Opcodes.ASM9, next) {
    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
        val removal = if (opcode == Opcodes.INVOKESTATIC) calls[StdlibCall(owner, name, descriptor)] else null
        if (removal != null) {
            val id = context.registerMutation(factory, "removed call to $owner::$name")
            if (context.shouldMutate(id)) {
                repeat(removal.pops) { mv.visitInsn(Opcodes.POP) }
                if (removal.toList) {
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC, "kotlin/collections/CollectionsKt", "toList",
                        "(Ljava/lang/Iterable;)Ljava/util/List;", false,
                    )
                }
                return
            }
        }
        mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
}
