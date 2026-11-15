package io.github.dan323.pitkotlin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.pitest.bytecode.analysis.ClassTree
import org.pitest.classinfo.ClassByteArraySource

/**
 * Decides from bytecode whether the value on top of the stack at some point cannot be null. Sound by
 * construction: it answers `true` only for shapes where a null is impossible, and `false` for everything else.
 *
 * A value is non-null when it comes from
 *  - a string constant or a string concatenation (`invokedynamic makeConcat...`),
 *  - `dup; ldc; Intrinsics.checkNotNullExpressionValue(...)`, which throws on null,
 *  - a call whose method is annotated `@NotNull` (the Kotlin compiler writes it on every non-null return of a
 *    non-private function, so this covers the stdlib), or `Object.toString()`,
 *  - a call to a private, static or final method of a class we can read whose every `areturn` is itself non-null,
 *  - a `checkcast` of such a value, or a local variable whose latest store on the straight-line path is one.
 */
internal class NonNull(private val source: ClassByteArraySource?, private val current: ClassTree?) {
    private val cache = HashMap<String, ClassNode?>()

    /** Is the value on top of the stack just before instruction [index] non-null? */
    fun valueBefore(method: MethodNode, insns: List<AbstractInsnNode>, index: Int, depth: Int = 0): Boolean {
        val targets = jumpTargets(method)
        return topBefore(insns, index, targets, depth)
    }

    private fun topBefore(insns: List<AbstractInsnNode>, index: Int, targets: Set<LabelNode>, depth: Int): Boolean {
        val i = previousReal(insns, index, targets) ?: return false
        val node = insns[i]
        return when (node) {
            is LdcInsnNode -> node.cst is String
            is InvokeDynamicInsnNode -> node.name.startsWith("makeConcat") && node.bsm.owner == "java/lang/invoke/StringConcatFactory"
            is TypeInsnNode -> node.opcode == Opcodes.CHECKCAST && topBefore(insns, i, targets, depth)
            is MethodInsnNode -> if (isNullCheck(node)) checkedValue(insns, i, targets) else nonNullReturn(node, depth)
            is VarInsnNode -> node.opcode == Opcodes.ALOAD && loadedValue(insns, i, node.`var`, targets, depth)
            else -> false
        }
    }

    /** `dup; ldc "msg"; invokestatic Intrinsics.checkNotNullExpressionValue` leaves the checked value on the stack. */
    private fun checkedValue(insns: List<AbstractInsnNode>, call: Int, targets: Set<LabelNode>): Boolean {
        val ldc = previousReal(insns, call, targets) ?: return false
        if (insns[ldc] !is LdcInsnNode) return false
        val dup = previousReal(insns, ldc, targets) ?: return false
        return insns[dup].opcode == Opcodes.DUP
    }

    private fun isNullCheck(call: MethodInsnNode) =
        call.owner == "kotlin/jvm/internal/Intrinsics" && call.name == "checkNotNullExpressionValue"

    /** The value stored to local [v] by the latest `astore` on the straight-line path before [from]. */
    private fun loadedValue(insns: List<AbstractInsnNode>, from: Int, v: Int, targets: Set<LabelNode>, depth: Int): Boolean {
        var i = from - 1
        while (i >= 0) {
            val n = insns[i]
            when {
                n is LabelNode && n in targets -> return false
                n is FrameNode -> return false
                n is JumpInsnNode || n is TableSwitchInsnNode || n is LookupSwitchInsnNode -> return false
                n.opcode in Opcodes.IRETURN..Opcodes.RETURN || n.opcode == Opcodes.ATHROW -> return false
                n is VarInsnNode && n.opcode == Opcodes.ASTORE && n.`var` == v -> return topBefore(insns, i, targets, depth)
                n is org.objectweb.asm.tree.IincInsnNode && n.`var` == v -> return false
            }
            i--
        }
        return false
    }

    private fun nonNullReturn(call: MethodInsnNode, depth: Int): Boolean {
        if (Type.getReturnType(call.desc).sort != Type.OBJECT) return false
        // Only final owners: an Object or CharSequence receiver may be a class whose toString() returns null.
        if (call.name == "toString" && call.desc == "()Ljava/lang/String;" &&
            call.owner in setOf("java/lang/String", "java/lang/StringBuilder")
        ) return true
        if (call.name == "<init>") return false
        val (owner, m) = resolve(call.owner, call.name, call.desc) ?: return false
        if (hasNotNull(m)) return true
        // No annotation (private methods carry none): look at the body if no subclass can override it.
        if (depth >= MAX_DEPTH) return false
        val access = m.access
        val closed = access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL) != 0 ||
            owner.access and Opcodes.ACC_FINAL != 0
        if (!closed || access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) return false
        val insns = m.instructions.toArray().toList()
        val returns = insns.indices.filter { insns[it].opcode == Opcodes.ARETURN }
        if (returns.isEmpty()) return false
        val targets = jumpTargets(m)
        return returns.all { topBefore(insns, it, targets, depth + 1) }
    }

    private fun resolve(owner: String, name: String, desc: String, hops: Int = 0): Pair<ClassNode, MethodNode>? {
        val node = classNode(owner) ?: return null
        node.methods.firstOrNull { it.name == name && it.desc == desc }?.let { return node to it }
        if (hops >= 4) return null
        val parents = listOfNotNull(node.superName) + node.interfaces
        return parents.firstNotNullOfOrNull { resolve(it, name, desc, hops + 1) }
    }

    private fun classNode(internalName: String): ClassNode? {
        if (current != null && current.name().asInternalName() == internalName) return current.rawNode()
        return cache.getOrPut(internalName) {
            val bytes = source?.getBytes(internalName.replace('/', '.'))?.orElse(null) ?: return@getOrPut null
            runCatching { ClassTree.fromBytes(bytes).rawNode() }.getOrNull()
        }
    }

    private fun hasNotNull(m: MethodNode): Boolean = isNotNullAnnotated(m)

    /** Previous real instruction, stopping at a label something jumps to (a merge point). */
    private fun previousReal(insns: List<AbstractInsnNode>, index: Int, targets: Set<LabelNode>): Int? {
        var i = index - 1
        while (i >= 0) {
            val n = insns[i]
            if (n is LabelNode && n in targets) return null
            if (n is FrameNode) return null
            if (n.opcode >= 0 && n.opcode != Opcodes.NOP) return i
            i--
        }
        return null
    }

    private fun jumpTargets(m: MethodNode): Set<LabelNode> {
        val out = HashSet<LabelNode>()
        for (n in m.instructions) {
            when (n) {
                is JumpInsnNode -> out += n.label
                is TableSwitchInsnNode -> { out += n.dflt; out += n.labels }
                is LookupSwitchInsnNode -> { out += n.dflt; out += n.labels }
            }
        }
        m.tryCatchBlocks.forEach { out += it.handler }
        return out
    }

    private companion object {
        const val MAX_DEPTH = 3
    }
}

private val NOT_NULL = setOf("Lorg/jetbrains/annotations/NotNull;", "Landroidx/annotation/NonNull;", "Llombok/NonNull;")

/** JSR-305 `@Nonnull(when = ...)`: only an absent `when` or `ALWAYS` means the result is never null. */
private val JSR305_NONNULL = setOf("Ljavax/annotation/Nonnull;", "Ljakarta/annotation/Nonnull;")

/** Does the method carry an annotation that promises a non-null result? */
internal fun isNotNullAnnotated(m: MethodNode): Boolean =
    (m.visibleAnnotations.orEmpty() + m.invisibleAnnotations.orEmpty()).any { a ->
        when (a.desc) {
            in NOT_NULL -> true
            in JSR305_NONNULL -> {
                val values = a.values.orEmpty()
                val whenIndex = values.indices.step(2).firstOrNull { values[it] == "when" }
                // An enum value is stored as [descriptor, constant name].
                val whenValue = whenIndex?.let { (values[it + 1] as? Array<*>)?.getOrNull(1) as? String }
                whenValue == null && whenIndex == null || whenValue == "ALWAYS"
            }
            else -> false
        }
    }
