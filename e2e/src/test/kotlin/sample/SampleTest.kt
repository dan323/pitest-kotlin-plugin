package sample

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleTest {
    @Test fun total() = assertEquals(10, Sample().total(listOf(1, 2, 3)))

    @Test fun home() = assertTrue(Sample().home().isNotEmpty())

    @Test fun describe() {
        val s = Sample()
        s.label = "ab"
        assertEquals("AB", s.describe())
    }

    @Test fun failure() = assertEquals("boom", Sample().failure(IllegalStateException("boom")))

    @Test fun stamp() {
        assertEquals("t5", Sample().stamp(5))
        assertEquals("none", Sample().stamp(null))
    }

    @Test fun compute() {
        var out = -1
        val s = Sample()
        suspend { s.compute(3) }.startCoroutine(Continuation(EmptyCoroutineContext) { out = it.getOrThrow() })
        assertEquals(4, out)
    }
}
