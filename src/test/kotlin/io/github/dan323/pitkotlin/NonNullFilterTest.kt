package io.github.dan323.pitkotlin

import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.MethodNode
import org.pitest.bytecode.analysis.ClassTree
import org.pitest.classinfo.ClassByteArraySource
import org.pitest.mutationtest.engine.MutationDetails
import org.pitest.mutationtest.engine.gregor.MethodMutatorFactory
import org.pitest.mutationtest.engine.gregor.config.Mutator
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals

class NonNullFilterTest {
    private val source = ClassByteArraySource { name ->
        Optional.ofNullable(javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readBytes() })
    }
    private val tree = ClassTree.fromBytes(source.getBytes("fixtures.NonNulls").orElseThrow())
    private val set = MutantSet(
        "NonNulls",
        listOf<MethodMutatorFactory>(
            KotlinElvisAlwaysDefaultMutator(), KotlinElvisNeverDefaultMutator(), KotlinSafeCallNeverNullMutator(),
        ) + Mutator.byName("REMOVE_CONDITIONALS_EQUAL_ELSE") + Mutator.byName("REMOVE_CONDITIONALS_EQUAL_IF"),
    )
    private val kept: List<MutationDetails> =
        KotlinNonNullFilter(source).also { it.begin(tree) }.intercept(set.mutants, set.mutater).toList()

    private fun names(ms: List<MutationDetails>, method: String) =
        ms.filter { it.method == method }.map { it.mutator.substringAfterLast('.') }.sorted()

    @Test
    fun `a let on a private function that returns a string concat loses the never default mutant`() {
        assertEquals(true, "KotlinElvisNeverDefaultMutator" in names(set.mutants, "letElvis"))
        assertEquals(false, "KotlinElvisNeverDefaultMutator" in names(kept, "letElvis"))
        // PIT's own "always jump" on the elvis jump goes with it; the one on the let's ifnull (a different jump) stays.
        assertEquals(
            names(set.mutants, "letElvis").count { it == "RemoveConditionalMutator_EQUAL_ELSE" } - 1,
            names(kept, "letElvis").count { it == "RemoveConditionalMutator_EQUAL_ELSE" },
        )
        // The always default side is a real mutant and stays.
        assertEquals(true, "KotlinElvisAlwaysDefaultMutator" in names(kept, "letElvis"))
    }

    @Test
    fun `a let on a function that may return null keeps everything`() {
        assertEquals(names(set.mutants, "nullableLetElvis"), names(kept, "nullableLetElvis"))
    }

    @Test
    fun `a let on a Java method checked by checkNotNullExpressionValue is non null`() {
        assertEquals(false, "KotlinElvisNeverDefaultMutator" in names(kept, "platformLet"))
    }

    /** The method called just before the `dup` of the jump this mutant sits on. */
    private fun calledBefore(m: MutationDetails): String {
        val insns = tree.methods().first { it.asLocation() == m.id.location }.instructions()
        val dup = insns.realBefore(m.instructionIndex)!!
        var i = insns.indexOf(dup)
        var n = insns.realBefore(i)!!
        // a generic call is followed by a checkcast to the element type
        while (n is org.objectweb.asm.tree.TypeInsnNode) { i = insns.indexOf(n); n = insns.realBefore(i)!! }
        return (n as org.objectweb.asm.tree.MethodInsnNode).name
    }

    @Test
    fun `in a find chain only the link after a NotNull stdlib call goes`() {
        val safe = set.mutants.filter { it.method == "matchChain" && it.mutator.endsWith("KotlinSafeCallNeverNullMutator") }
        val dropped = safe - kept.toSet()
        val stayed = safe.filter { it in kept }
        // find()? stays (nullable), getGroupValues()? goes (@NotNull), get(1)? stays (generic element, not provable).
        assertEquals(listOf("getGroupValues"), dropped.map(::calledBefore))
        assertEquals(setOf("find\$default", "get"), stayed.map(::calledBefore).toSet())
        // The elvis after trim().toString() stays: toString() of an arbitrary CharSequence may be null.
        assertEquals(true, "KotlinElvisNeverDefaultMutator" in names(kept, "matchChain"))
    }

    private fun method(vararg annotations: AnnotationNode) =
        MethodNode().also { it.visibleAnnotations = annotations.toList() }

    private fun nonnull(desc: String, whenValue: String?) = AnnotationNode(desc).also {
        if (whenValue != null) it.values = listOf("when", arrayOf("Ljavax/annotation/meta/When;", whenValue))
    }

    @Test
    fun `JSR-305 Nonnull counts only when it is ALWAYS or has no when`() {
        val jsr = "Ljavax/annotation/Nonnull;"
        assertEquals(true, isNotNullAnnotated(method(nonnull(jsr, null))))
        assertEquals(true, isNotNullAnnotated(method(nonnull(jsr, "ALWAYS"))))
        for (w in listOf("MAYBE", "NEVER", "UNKNOWN")) assertEquals(false, isNotNullAnnotated(method(nonnull(jsr, w))), w)
        assertEquals(false, isNotNullAnnotated(method(nonnull("Ljakarta/annotation/Nonnull;", "MAYBE"))))
        assertEquals(true, isNotNullAnnotated(method(AnnotationNode("Lorg/jetbrains/annotations/NotNull;"))))
        assertEquals(false, isNotNullAnnotated(method(AnnotationNode("Lorg/jetbrains/annotations/Nullable;"))))
    }

    @Test
    fun `a nullable map lookup keeps every mutant`() {
        assertEquals(names(set.mutants, "mapElvis"), names(kept, "mapElvis"))
    }
}
