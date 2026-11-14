package io.github.dan323.pitkotlin

import org.pitest.mutationtest.engine.gregor.config.Mutator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElvisMutatorsTest {
    private val always = { MutantSet("Elvis", KotlinElvisAlwaysDefaultMutator()) }
    private val never = { MutantSet("Elvis", KotlinElvisNeverDefaultMutator()) }

    @Test
    fun `one mutant per elvis and none for safe calls`() {
        val set = always()
        val methods = set.mutants.groupingBy { it.method }.eachCount()
        for (m in listOf("plain", "fromMap", "earlyReturn", "orThrow", "local", "userNullCheck")) {
            assertEquals(1, methods[m], "expected exactly one mutant in $m, got $methods")
        }
        assertEquals(2, methods["chain"], methods.toString())
        assertNull(methods["safeCallOnly"], "safe call must not match: $methods")
    }

    @Test
    fun `lateinit guards and double bang are not matched`() {
        assertTrue(MutantSet("NullChecks", KotlinElvisAlwaysDefaultMutator()).mutants.isEmpty())
        assertTrue(MutantSet("NullChecks", KotlinElvisNeverDefaultMutator()).mutants.isEmpty())
    }

    @Test
    fun `always default ignores a non null left side`() {
        val set = always()
        val original = fixtures.Elvis()
        assertEquals("x", original.plain("x"))

        val mutated = set.instance(set.inMethod("plain").single())
        assertEquals("default", mutated.call("plain", "x"))
        assertEquals("default", mutated.call("plain", null))
    }

    @Test
    fun `never default leaks a null left side`() {
        val set = never()
        assertEquals("default", fixtures.Elvis().plain(null))

        val mutated = set.instance(set.inMethod("plain").single())
        assertEquals("x", mutated.call("plain", "x"))
        assertNull(mutated.call("plain", null))
    }

    @Test
    fun `throwing and returning defaults mutate correctly`() {
        val a = always()
        assertFailsWith<IllegalStateException> {
            a.instance(a.inMethod("orThrow").single()).call("orThrow", "present")
        }
        val n = never()
        assertNull(n.instance(n.inMethod("orThrow").single()).call("orThrow", null))

        assertEquals("early", a.instance(a.inMethod("earlyReturn").single()).call("earlyReturn", "x"))
        assertEquals("null!", n.instance(n.inMethod("earlyReturn").single()).call("earlyReturn", null))
    }

    @Test
    fun `chained elvis gets an independent mutant for each link`() {
        val a = always()
        val (first, second) = a.inMethod("chain")
        assertEquals("b", a.instance(first).call("chain", "a", "b")) // outer `a ?:` always takes the rest
        assertEquals("a", a.instance(second).call("chain", "a", "b")) // inner `b ?:` irrelevant when a is set
        assertEquals("z", a.instance(second).call("chain", null, "b")) // inner always takes "z"
    }

    @Test
    fun `local variable and plain null check shapes are mutated too`() {
        val a = always()
        assertEquals("d", a.instance(a.inMethod("local").single()).call("local", "x"))
        assertEquals("n", a.instance(a.inMethod("userNullCheck").single()).call("userNullCheck", "x"))
    }

    @Test
    fun `all untouched mutants still verify and behave like the original`() {
        // Every generated mutant must load (JVM verifier) even when dead code follows the goto.
        for (set in listOf(always(), never())) {
            for (m in set.mutants) set.instance(m)
        }
    }

    @Test
    fun `mutants are attributed to the coverage block of the jump not the default branch`() {
        // PIT only runs tests that covered the mutant's block. `always default` is killed by tests on the
        // non-null path, which never enter the default branch, so it must share the jump's block.
        for (name in listOf("plain", "orThrow", "earlyReturn")) {
            val elvis = always().inMethod(name).single()
            val conditional = MutantSet("Elvis", Mutator.byName("REMOVE_CONDITIONALS_EQUAL_ELSE").single())
                .mutants.single { it.method == name && it.id.firstIndex == elvis.id.firstIndex }
            assertEquals(conditional.blocks, elvis.blocks, "block of $name")
        }
    }

    @Test
    fun `mutators are registered by name and through the KOTLIN group`() {
        val ids = Mutator.allMutatorIds()
        assertTrue("KOTLIN_ELVIS_ALWAYS_DEFAULT" in ids && "KOTLIN_ELVIS_NEVER_DEFAULT" in ids, ids.toString())

        val viaGroup = Mutator.fromStrings(listOf("KOTLIN")).map { it.name }.toSet()
        assertEquals(setOf("KOTLIN_ELVIS_ALWAYS_DEFAULT", "KOTLIN_ELVIS_NEVER_DEFAULT", "KOTLIN_SAFECALL_NEVER_NULL", "KOTLIN_STDLIB_SWAP"), viaGroup)

        val withDefaults = Mutator.fromStrings(listOf("DEFAULTS", "KOTLIN")).map { it.name }.toSet()
        assertTrue(viaGroup.all { it in withDefaults } && "MATH" in withDefaults, withDefaults.toString())
        assertTrue("KOTLIN_ELVIS_ALWAYS_DEFAULT" !in Mutator.newDefaults().map { it.name }, "must be opt-in")
    }
}
