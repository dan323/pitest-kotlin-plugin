package io.github.dan323.pitkotlin

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

    @Test
    fun `in a find chain only the link after a NotNull stdlib call goes`() {
        val before = set.mutants.filter { it.method == "matchChain" && it.mutator.endsWith("KotlinSafeCallNeverNullMutator") }
        val after = kept.filter { it.method == "matchChain" && it.mutator.endsWith("KotlinSafeCallNeverNullMutator") }
        // find()? stays (nullable), getGroupValues()? goes (@NotNull), get(1)? stays (generic element, not provable).
        assertEquals(3, before.size)
        assertEquals(2, after.size)
        // the elvis after trim().toString() is non null
        assertEquals(false, "KotlinElvisNeverDefaultMutator" in names(kept, "matchChain"))
    }

    @Test
    fun `a nullable map lookup keeps every mutant`() {
        assertEquals(names(set.mutants, "mapElvis"), names(kept, "mapElvis"))
    }
}
