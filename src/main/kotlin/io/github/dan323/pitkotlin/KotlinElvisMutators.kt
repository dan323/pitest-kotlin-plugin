    package io.github.dan323.pitkotlin

import org.pitest.mutationtest.engine.gregor.MethodInfo
import org.pitest.mutationtest.engine.gregor.MethodMutatorFactory
import org.pitest.mutationtest.engine.gregor.MutationContext
import org.pitest.mutationtest.engine.gregor.config.MutatorGroup
import org.pitest.reloc.asm.Handle
import org.pitest.reloc.asm.Label
import org.pitest.reloc.asm.MethodVisitor
import org.pitest.reloc.asm.Opcodes

/**
 * Mutators for Kotlin's elvis operator `a ?: b`, which compiles to
 * `dup; ifnonnull L; pop; <b>; L:`.
 *
 * PIT already mutates this jump, but only one side by default: `REMOVE_CONDITIONALS_EQUAL_ELSE`
 * (in `DEFAULTS`) is byte-for-byte `KOTLIN_ELVIS_NEVER_DEFAULT`, and `REMOVE_CONDITIONALS_EQUAL_IF`
 * (in `STRONGER`) is `KOTLIN_ELVIS_ALWAYS_DEFAULT`. So `ALWAYS_DEFAULT` is the mutant `DEFAULTS` lacks,
 * and the pair together replaces `NEGATE_CONDITIONALS`, which flips both sides at once and is killed by
 * a test on either. Each mutant here names the side it tests, so a survivor says which side is untested:
 *  - `KOTLIN_ELVIS_ALWAYS_DEFAULT`: `a ?: b` always evaluates `b` (killed by a non-null `a`).
 *  - `KOTLIN_ELVIS_NEVER_DEFAULT`: `a ?: b` always yields `a`, even when null (killed by a null `a`).
 *
 * `FKTELVISDEDUP` removes the built-in duplicates when these are enabled.
 *
 * Safe calls (`?.`, `dup; ifnull`), `lateinit` guards and `!!` have different shapes and are not
 * matched. An explicit `a ?: null` is matched too, where `NEVER_DEFAULT` is equivalent to the original.
 */
class KotlinElvisAlwaysDefaultMutator : MethodMutatorFactory {
    override fun create(context: MutationContext, info: MethodInfo, next: MethodVisitor): MethodVisitor =
        NullBranchMethodVisitor(this, context, next, "elvis: always evaluated the right-hand side", Opcodes.IFNONNULL, jumpAlways = false)

    override fun getGloballyUniqueId(): String = javaClass.name

    override fun getName(): String = "KOTLIN_ELVIS_ALWAYS_DEFAULT"
}

class KotlinElvisNeverDefaultMutator : MethodMutatorFactory {
    override fun create(context: MutationContext, info: MethodInfo, next: MethodVisitor): MethodVisitor =
        NullBranchMethodVisitor(this, context, next, "elvis: never evaluated the right-hand side", Opcodes.IFNONNULL, jumpAlways = true)

    override fun getGloballyUniqueId(): String = javaClass.name

    override fun getName(): String = "KOTLIN_ELVIS_NEVER_DEFAULT"
}

/**
 * `a?.f()` compiles to `dup; ifnull L; <call>; goto E; L: pop; aconst_null; E:`. PIT's `DEFAULTS` already
 * has the "always null" side (`REMOVE_CONDITIONALS_EQUAL_ELSE`); this adds the side it lacks, where the
 * call is never short-circuited and so runs on a null receiver (`REMOVE_CONDITIONALS_EQUAL_IF`, in
 * `STRONGER`, produces the same mutant but for every conditional). It is killed only by a test that
 * passes a null receiver, and fails with a NullPointerException there.
 *
 * `lateinit` guards share the `dup; ifnull` shape; `FKTINTRINSICS` removes those.
 */
class KotlinSafeCallNeverNullMutator : MethodMutatorFactory {
    override fun create(context: MutationContext, info: MethodInfo, next: MethodVisitor): MethodVisitor =
        NullBranchMethodVisitor(this, context, next, "safe call: never short-circuited on a null receiver", Opcodes.IFNULL, jumpAlways = false)

    override fun getGloballyUniqueId(): String = javaClass.name

    override fun getName(): String = "KOTLIN_SAFECALL_NEVER_NULL"
}

/** Makes `KOTLIN` select all Kotlin mutators, e.g. `mutators = ["DEFAULTS", "KOTLIN"]`. */
class KotlinMutatorGroup : MutatorGroup {
    override fun register(groups: MutableMap<String, MutableList<MethodMutatorFactory>>) {
        groups["KOTLIN"] = gather(groups, "KOTLIN_ELVIS_ALWAYS_DEFAULT", "KOTLIN_ELVIS_NEVER_DEFAULT", "KOTLIN_SAFECALL_NEVER_NULL", "KOTLIN_STDLIB_SWAP", "KOTLIN_REMOVE_DISTINCT", "KOTLIN_REMOVE_SORTED")
    }
}

/**
 * Recognises `DUP, <ifnull|ifnonnull> L` and registers the mutation at the jump.
 *
 * Registering at the jump matters: PIT attributes a mutant to the coverage block it is registered
 * in, and only runs tests that covered that block. The jump's block is executed by every test that
 * reaches the elvis; the default branch's block only by tests that take the null path, which would
 * hide `ALWAYS_DEFAULT` from the tests that can kill it.
 *
 * Both replacements keep the stack exactly as the original jump would: the jump pops one copy of the
 * value, so `POP` (fall through into the default) or `POP; GOTO L` (skip the default) are well typed
 * wherever the original was. Nothing is reordered; a lone `DUP` is replayed unchanged.
 */
internal class NullBranchMethodVisitor(
    private val factory: MethodMutatorFactory,
    private val context: MutationContext,
    next: MethodVisitor,
    private val description: String,
    private val jumpOpcode: Int,
    private val jumpAlways: Boolean,
) : MethodVisitor(Opcodes.ASM9, next) {
    private var pendingDup = false

    /** Replays a held DUP, if any, before any event other than the jump we are waiting for. */
    private fun releaseDup() {
        if (pendingDup) {
            pendingDup = false
            mv.visitInsn(Opcodes.DUP)
        }
    }

    override fun visitInsn(opcode: Int) {
        releaseDup()
        if (opcode == Opcodes.DUP) pendingDup = true else mv.visitInsn(opcode)
    }

    override fun visitJumpInsn(opcode: Int, label: Label) {
        if (!(pendingDup && opcode == jumpOpcode)) {
            releaseDup()
            mv.visitJumpInsn(opcode, label)
            return
        }
        pendingDup = false
        mv.visitInsn(Opcodes.DUP)
        val id = context.registerMutation(factory, description)
        if (context.shouldMutate(id)) {
            mv.visitInsn(Opcodes.POP)
            if (jumpAlways) mv.visitJumpInsn(Opcodes.GOTO, label)
        } else {
            mv.visitJumpInsn(jumpOpcode, label)
        }
    }

    override fun visitLabel(label: Label) { releaseDup(); mv.visitLabel(label) }

    override fun visitLineNumber(line: Int, start: Label) { releaseDup(); mv.visitLineNumber(line, start) }

    override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any>?, numStack: Int, stack: Array<out Any>?) {
        releaseDup(); mv.visitFrame(type, numLocal, local, numStack, stack)
    }

    override fun visitIntInsn(opcode: Int, operand: Int) { releaseDup(); mv.visitIntInsn(opcode, operand) }

    override fun visitVarInsn(opcode: Int, varIndex: Int) { releaseDup(); mv.visitVarInsn(opcode, varIndex) }

    override fun visitTypeInsn(opcode: Int, type: String) { releaseDup(); mv.visitTypeInsn(opcode, type) }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        releaseDup(); mv.visitFieldInsn(opcode, owner, name, descriptor)
    }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
        releaseDup(); mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    override fun visitInvokeDynamicInsn(name: String, descriptor: String, handle: Handle, vararg args: Any) {
        releaseDup(); mv.visitInvokeDynamicInsn(name, descriptor, handle, *args)
    }

    override fun visitLdcInsn(value: Any) { releaseDup(); mv.visitLdcInsn(value) }

    override fun visitIincInsn(varIndex: Int, increment: Int) { releaseDup(); mv.visitIincInsn(varIndex, increment) }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
        releaseDup(); mv.visitTableSwitchInsn(min, max, dflt, *labels)
    }

    override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<out Label>) {
        releaseDup(); mv.visitLookupSwitchInsn(dflt, keys, labels)
    }

    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
        releaseDup(); mv.visitMultiANewArrayInsn(descriptor, numDimensions)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        releaseDup()
        super.visitMaxs(maxStack, maxLocals)
    }

    override fun visitEnd() {
        releaseDup()
        super.visitEnd()
    }
}
