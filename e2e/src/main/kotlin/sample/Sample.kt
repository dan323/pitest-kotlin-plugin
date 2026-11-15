package sample

import kotlin.coroutines.suspendCoroutine

class Sample {
    lateinit var label: String

    fun total(values: List<Int>): Int = values.map { it * 2 }.filter { it > 2 }.sum()

    fun home(): String = System.getenv("PATH")

    fun describe(): String = label.uppercase()

    fun failure(e: Exception): String = e.message ?: "unknown error"

    suspend fun fetch(x: Int): Int = suspendCoroutine { it.resumeWith(Result.success(x)) }

    suspend fun compute(x: Int): Int {
        val first = fetch(x)
        return if (first > 1) fetch(first) + 1 else 0
    }
}
