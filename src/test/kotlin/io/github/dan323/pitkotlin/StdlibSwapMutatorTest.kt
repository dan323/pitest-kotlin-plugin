package io.github.dan323.pitkotlin

import org.objectweb.asm.Type
import java.lang.invoke.MethodType
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StdlibSwapMutatorTest {
    private val set by lazy { MutantSet("Swaps", KotlinStdlibSwapMutator()) }

    /** method, argument, result of the original code, result once the call is swapped. */
    private val cases = listOf(
        Case("first", listOf(1, 2, 3), 1, 3),
        Case("last", listOf(1, 2, 3), 3, 1),
        Case("firstOrNull", listOf(1, 2, 3), 1, 3),
        Case("take", listOf(1, 2, 3, 4), listOf(1, 2), listOf(3, 4)),
        Case("takeLast", listOf(1, 2, 3, 4), listOf(3, 4), listOf(1, 2)),
        Case("drop", listOf(1, 2, 3, 4), listOf(3, 4), listOf(1, 2)),
        Case("maxOrNull", listOf(1, 5, 3), 5, 1),
        Case("minOrNull", listOf(1, 5, 3), 1, 5),
        Case("sorted", listOf(3, 1, 2), listOf(1, 2, 3), listOf(3, 2, 1)),
        Case("atLeast", 1, 5, 1),
        Case("atMost", 9, 5, 9),
        Case("startsWith", "abc", true, false),
        Case("endsWith", "xab", true, false),
        Case("strTake", "abcd", "ab", "cd"),
        Case("strDrop", "abcd", "cd", "ab"),
        Case("before", "a-b", "a", "b"),
        Case("after", "a-b", "b", "a"),
        Case("indexOf", "abcb", 1, 3),
        Case("strFirst", "abc", 'a', 'c'),
        Case("seqTake", sequenceOf(1, 2, 3), listOf(1, 2), listOf(3)),
        Case("trimS", "  a  ", "a  ", "  a"),
        Case("removePrefix", "abcab", "cab", "abc"),
        Case("trimChars", "xaxx", "axx", "xa"),
        Case("indexOfChar", "abcb", 1, 3),
        Case("beforeOrElse", "a-b", "a", "b"),
        Case("startsWithChar", "abc", true, false),
    )

    private class Case(val method: String, val arg: Any, val original: Any?, val mutated: Any?)

    @Test
    fun `every table entry exists in the stdlib on the classpath with the stated descriptor`() {
        val loader = javaClass.classLoader
        for (call in StdlibSwaps.partners.keys + StdlibSwaps.partners.values) {
            val type = MethodType.fromMethodDescriptorString(call.descriptor, loader)
            val owner = Class.forName(call.owner.replace('/', '.'), false, loader)
            // Overloads can differ only in their return type (maxOrNull for Comparable/Double/Float), so look at all of them.
            val candidates = owner.methods.filter { it.name == call.name && it.parameterTypes.contentEquals(type.parameterArray()) }
            assertTrue(candidates.isNotEmpty(), "${call.owner}.${call.name}${call.descriptor} does not exist")
            val method = candidates.firstOrNull { Type.getDescriptor(it.returnType) == Type.getDescriptor(type.returnType()) }
                ?: error("${call.name}${call.descriptor}: no overload returns ${type.returnType()}, found ${candidates.map { it.returnType }}")
            assertTrue(Modifier.isStatic(method.modifiers), "$call must be static")
        }
    }

    @Test
    fun `swap partners are symmetric and share their descriptor and owner`() {
        for ((a, b) in StdlibSwaps.partners) {
            assertEquals(a, StdlibSwaps.partners[b], "partner of $b")
            assertEquals(a.descriptor, b.descriptor)
            assertEquals(a.owner, b.owner)
            assertNotEquals(a.name, b.name)
        }
    }

    @Test
    fun `each swappable call in the fixture yields exactly one mutant`() {
        val counts = set.mutants.groupingBy { it.method }.eachCount()
        for (c in cases) assertEquals(1, counts[c.method], "${c.method}: $counts")
        for (m in listOf("maxInt", "minInt")) assertEquals(1, counts[m], "$m: $counts")
    }

    @Test
    fun `swapped calls change the result in the expected way`() {
        val original = fixtures.Swaps()
        for (c in cases) {
            assertEquals(c.original, original.call(c.method, c.arg), "original ${c.method}")
            val mutated = set.instance(set.inMethod(c.method).single())
            assertEquals(c.mutated, mutated.call(c.method, c.arg), "mutated ${c.method}")
        }
    }

    @Test
    fun `math max and min swap`() {
        val max = set.instance(set.inMethod("maxInt").single())
        val min = set.instance(set.inMethod("minInt").single())
        assertEquals(1, max.call("maxInt", 1, 2))
        assertEquals(2, min.call("minInt", 1, 2))
    }

    @Test
    fun `description names the call and its replacement`() {
        val m = set.inMethod("first").single()
        assertEquals("swapped kotlin/collections/CollectionsKt::first for last", m.description)
    }

    @Test
    fun `every generated mutant verifies`() {
        for (m in set.mutants) set.instance(m)
    }

    @Test
    fun `code without swappable calls has no mutants`() {
        assertTrue(MutantSet("NullChecks", KotlinStdlibSwapMutator()).mutants.isEmpty())
        assertTrue(MutantSet("Elvis", KotlinStdlibSwapMutator()).mutants.isEmpty())
    }
}
