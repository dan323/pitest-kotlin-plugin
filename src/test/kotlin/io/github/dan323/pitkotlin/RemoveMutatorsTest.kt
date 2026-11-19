package io.github.dan323.pitkotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoveMutatorsTest {
    private val distinct by lazy { MutantSet("Removals", KotlinRemoveDistinctMutator()) }
    private val sorted by lazy { MutantSet("Removals", KotlinRemoveSortedMutator()) }

    private class Case(val method: String, val arg: Any, val original: Any?, val mutated: Any?)

    private val distinctCases = listOf(
        Case("distinct", listOf(1, 1, 2, 1), listOf(1, 2), listOf(1, 1, 2, 1)),
        Case("seqDistinct", sequenceOf(1, 1, 2, 1), listOf(1, 2), listOf(1, 1, 2, 1)),
    )

    private val sortedCases = listOf(
        Case("sorted", listOf(3, 1, 2), listOf(1, 2, 3), listOf(3, 1, 2)),
        Case("sortedDescending", listOf(3, 1, 2), listOf(3, 2, 1), listOf(3, 1, 2)),
        Case("sortedWith", listOf(1, 3, 2), listOf(3, 2, 1), listOf(1, 3, 2)),
        Case("seqSorted", sequenceOf(3, 1, 2), listOf(1, 2, 3), listOf(3, 1, 2)),
        Case("seqSortedDescending", sequenceOf(3, 1, 2), listOf(3, 2, 1), listOf(3, 1, 2)),
        Case("seqSortedWith", sequenceOf(1, 3, 2), listOf(3, 2, 1), listOf(1, 3, 2)),
    )

    private fun check(set: MutantSet, cases: List<Case>) {
        val original = fixtures.Removals()
        for (c in cases) {
            assertEquals(c.original, original.call(c.method, c.arg), "original ${c.method}")
            val mutant = set.inMethod(c.method).single()
            assertEquals(c.mutated, set.instance(mutant).call(c.method, c.arg), "mutated ${c.method}")
        }
    }

    @Test
    fun `removing distinct keeps the duplicates`() = check(distinct, distinctCases)

    @Test
    fun `removing sorted keeps the input order`() = check(sorted, sortedCases)

    @Test
    fun `each mutator touches only its own calls`() {
        assertEquals(distinctCases.map { it.method }.sorted(), distinct.mutants.map { it.method }.sorted())
        assertEquals(sortedCases.map { it.method }.sorted(), sorted.mutants.map { it.method }.sorted())
    }

    @Test
    fun `distinctBy and code without these calls are not mutated`() {
        for (m in listOf("distinctBy", "noCalls")) {
            assertTrue(distinct.inMethod(m).isEmpty() && sorted.inMethod(m).isEmpty(), m)
        }
    }

    @Test
    fun `every generated mutant verifies and the description names the call`() {
        for (m in distinct.mutants) distinct.instance(m)
        for (m in sorted.mutants) sorted.instance(m)
        assertEquals("removed call to kotlin/collections/CollectionsKt::distinct", distinct.inMethod("distinct").single().description)
    }

    @Test
    fun `mutator names`() {
        assertEquals("KOTLIN_REMOVE_DISTINCT", KotlinRemoveDistinctMutator().name)
        assertEquals("KOTLIN_REMOVE_SORTED", KotlinRemoveSortedMutator().name)
    }
}
