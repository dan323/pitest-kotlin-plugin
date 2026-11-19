package fixtures

import java.util.concurrent.TimeUnit
import kotlin.coroutines.suspendCoroutine

class NullChecks(val name: String) {
    lateinit var late: String

    fun greet(other: String): String = "hi " + other + name.length

    fun readLate(): Int = late.length

    fun bang(s: String?): Int = s!!.length
}

class WhenUser {
    fun describe(unit: TimeUnit): Int =
        when (unit) {
            TimeUnit.SECONDS -> 1
            TimeUnit.MINUTES -> 2
            else -> 3
        }
}

class Defaults {
    fun add(a: Int = 5, b: Int = 3): Int = a + b
}

data class Generated(val a: Int, val b: String)

data class CustomToString(val a: Int) {
    override fun toString(): String = if (a > 0) "positive" else "other"
}

class Coroutines {
    suspend fun inner(x: Int): Int = suspendCoroutine { it.resumeWith(Result.success(x)) }

    suspend fun outer(x: Int): Int {
        val first = inner(x)
        return if (first > 1) inner(first) + 1 else 0
    }
}

inline fun twice(block: () -> Int): Int = block() + block()

class Inlines {
    fun mapped(list: List<Int>): List<Int> = list.map { it + 1 }.filter { it > 2 }

    fun own(): Int = twice { 3 }

    fun platform(): String = System.getenv("HOME")
}

class Lambdas {
    fun unitLambda(): () -> Unit = { println("x") }

    fun tailSuspend(c: Coroutines): suspend () -> Int = { c.inner(1) }
}

suspend inline fun forwardInline(block: () -> Int): Int = Coroutines().inner(block()) + 1

suspend inline fun <T> scoped(crossinline block: suspend () -> T): T = kotlinx.coroutines.coroutineScope { block() }

class InlineSuspend {
    suspend fun use(): Int = forwardInline { 2 }

    suspend fun nested(c: Coroutines): Int = scoped { scoped { c.inner(1) } + 1 }
}

class Elvis {
    fun plain(s: String?): String = s ?: "default"

    fun afterSafeCall(s: String?): Int = s?.length ?: 0

    fun fromMap(m: Map<String, String>, k: String): String = m[k] ?: "none"

    fun earlyReturn(s: String?): String {
        val t = s ?: return "early"
        return t + "!"
    }

    fun orThrow(s: String?): String = s ?: throw IllegalStateException("x")

    fun local(s: String?): String {
        val x = s
        return x ?: "d"
    }

    fun chain(a: String?, b: String?): String = a ?: b ?: "z"

    fun safeCallOnly(s: String?): String? = s?.trim()

    fun userNullCheck(s: String?): String = if (s == null) "n" else s

    fun notElvis(x: Int): Int = if (x > 0) 1 else 2
}

class SafeCalls {
    var counter = 0

    fun value(s: String?): String? = s?.trim()

    fun length(s: String?): Int? = s?.length

    fun chained(s: String?): Int? = s?.trim()?.length

    fun statement(s: String?) {
        s?.let { counter += it.length }
    }

    fun letResult(s: String?): String? = s?.let { it + "!" }
}

class Swaps {
    fun first(l: List<Int>): Int = l.first()
    fun last(l: List<Int>): Int = l.last()
    fun firstOrNull(l: List<Int>): Int? = l.firstOrNull()
    fun take(l: List<Int>): List<Int> = l.take(2)
    fun takeLast(l: List<Int>): List<Int> = l.takeLast(2)
    fun drop(l: List<Int>): List<Int> = l.drop(2)
    fun maxOrNull(l: List<Int>): Int? = l.maxOrNull()
    fun minOrNull(l: List<Int>): Int? = l.minOrNull()
    fun sorted(l: List<Int>): List<Int> = l.sorted()
    fun atLeast(x: Int): Int = x.coerceAtLeast(5)
    fun atMost(x: Int): Int = x.coerceAtMost(5)
    fun startsWith(s: String): Boolean = s.startsWith("ab")
    fun endsWith(s: String): Boolean = s.endsWith("ab")
    fun strTake(s: String): String = s.take(2)
    fun strDrop(s: String): String = s.drop(2)
    fun before(s: String): String = s.substringBefore("-")
    fun after(s: String): String = s.substringAfter("-")
    fun indexOf(s: String): Int = s.indexOf("b")
    fun strFirst(s: String): Char = s.first()
    fun seqTake(s: Sequence<Int>): List<Int> = s.take(2).toList()
    fun maxInt(a: Int, b: Int): Int = maxOf(a, b)
    fun minInt(a: Int, b: Int): Int = kotlin.math.min(a, b)
    fun trimS(s: String): String = s.trimStart()
    fun removePrefix(s: String): String = s.removePrefix("ab")
    fun trimChars(s: String): String = s.trimStart('x')
    fun indexOfChar(s: String): Int = s.indexOf('b')
    fun beforeOrElse(s: String): String = s.substringBefore("-", "none")
    fun startsWithChar(s: String): Boolean = s.startsWith('a')
}

class Predicates {
    fun filter(l: List<Int>): List<Int> = l.filter { it > 2 }
    fun filterNot(l: List<Int>): List<Int> = l.filterNot { it > 2 }
    fun any(l: List<Int>): Boolean = l.any { it > 2 }
    fun all(l: List<Int>): Boolean = l.all { it > 2 }
    fun none(l: List<Int>): Boolean = l.none { it > 2 }
    fun count(l: List<Int>): Int = l.count { it > 2 }
    fun first(l: List<Int>): Int = l.first { it > 2 }
    fun find(l: List<Int>): Int? = l.find { it > 2 }
    fun takeWhile(l: List<Int>): List<Int> = l.takeWhile { it < 3 }
    fun partition(l: List<Int>): Pair<List<Int>, List<Int>> = l.partition { it > 2 }
}

class Messages {
    fun elvis(e: Exception): String = e.message ?: "no message"

    fun localized(e: Throwable): String = e.localizedMessage ?: "none"

    fun safeCall(e: Exception): Int? = e.message?.length

    fun otherElvis(s: String?): String = s ?: "default"

    fun otherSafeCall(s: String?): Int? = s?.length

    // A property called `message` that is not a Throwable's is still a getter named getMessage, so it is dropped too (documented).
    fun explicitCheck(e: Exception): String = if (e.message == null) "none" else e.message!!
}

class NonNulls {
    private fun stamp(n: Long): String = "t$n"

    private fun maybe(n: Long): String? = if (n > 0) "t$n" else null

    private fun viaJava(n: Long): java.time.Instant = java.time.Instant.EPOCH.plusSeconds(n)

    fun letElvis(l: Long?): String = l?.let { stamp(it) } ?: "none"

    fun nullableLetElvis(l: Long?): String = l?.let { maybe(it) } ?: "none"

    fun platformLet(l: Long?): java.time.Instant = l?.let { viaJava(it) } ?: java.time.Instant.MAX

    fun matchChain(r: Regex, v: String): String = r.find(v)?.groupValues?.get(1)?.trim() ?: "none"

    fun mapElvis(m: Map<String, String>, k: String): String = m[k] ?: "d"
}

enum class Color { RED, GREEN, BLUE }

sealed class Shape {
    class Circle(val r: Int) : Shape()
    class Square(val s: Int) : Shape()
    object Empty : Shape()
}

sealed interface Token
class Word(val text: String) : Token
class Number(val value: Int) : Token

class Whens {
    fun enumExpr(c: Color): Int = when (c) {
        Color.RED -> 1
        Color.GREEN -> 2
        Color.BLUE -> 3
    }

    fun enumStmt(c: Color): String {
        var out = ""
        when (c) {
            Color.RED -> out = "r"
            Color.GREEN -> out = "g"
            Color.BLUE -> out = "b"
        }
        return out
    }

    fun sealedExpr(s: Shape): Int = when (s) {
        is Shape.Circle -> s.r * 2
        is Shape.Square -> s.s * 4
        Shape.Empty -> 0
    }

    fun sealedInterface(t: Token): String = when (t) {
        is Word -> t.text
        is Number -> t.value.toString()
    }

    fun withElse(c: Color): Int = when (c) {
        Color.RED -> 1
        else -> 0
    }

    fun explicitSealedElse(t: Token): String = when (t) {
        is Word -> t.text
        else -> throw NoWhenBranchMatchedException()
    }

    fun explicitEnumElse(c: Color): Int = when (c) {
        Color.RED -> 1
        Color.GREEN -> 2
        else -> throw NoWhenBranchMatchedException()
    }

    fun oneLineEnumElse(c: Color): Int = when (c) { Color.RED -> 1; Color.GREEN -> 2; else -> throw NoWhenBranchMatchedException() }

    fun oneLineSealedElse(t: Token): String = when (t) { is Word -> t.text; else -> throw NoWhenBranchMatchedException() }

    fun oneLineCaseThrow(x: Int): Int = when (x) { 1 -> throw NoWhenBranchMatchedException(); 2 -> 2; 3 -> 3; else -> 4 }

    fun laterSwitchCaseThrow(x: Int): Int {
        val y = when (x) {
            1 -> throw NoWhenBranchMatchedException()
            2 -> 2
            3 -> 3
            else -> 4
        }
        return y
    }

    fun madeWithMessage(): Exception = NoWhenBranchMatchedException("user failure")

    fun madeAndReturned(): Exception = NoWhenBranchMatchedException()

    fun thrownWithMessage(x: Int): Int {
        if (x > 0) throw NoWhenBranchMatchedException("no branch")
        return x
    }

    fun plain(x: Int): Int = when (x) {
        1 -> 10
        2 -> 20
        else -> 0
    }
}

class Removals {
    fun distinct(l: List<Int>): List<Int> = l.distinct()
    fun sorted(l: List<Int>): List<Int> = l.sorted()
    fun sortedDescending(l: List<Int>): List<Int> = l.sortedDescending()
    fun sortedWith(l: List<Int>): List<Int> = l.sortedWith(Comparator { a, b -> b - a })
    fun seqDistinct(s: Sequence<Int>): List<Int> = s.distinct().toList()
    fun seqSorted(s: Sequence<Int>): List<Int> = s.sorted().toList()
    fun seqSortedDescending(s: Sequence<Int>): List<Int> = s.sortedDescending().toList()
    fun seqSortedWith(s: Sequence<Int>): List<Int> = s.sortedWith(Comparator { a, b -> b - a }).toList()
    fun distinctBy(l: List<Int>): List<Int> = l.distinctBy { it % 2 }
    fun noCalls(l: List<Int>): List<Int> = l.toList()
}
