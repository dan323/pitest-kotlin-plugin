package io.github.dan323.pitkotlin

import org.pitest.mutationtest.build.MutationInterceptorFactory
import org.pitest.mutationtest.build.InterceptorParameters
import org.pitest.mutationtest.config.ReportOptions
import org.pitest.plugin.FeatureSetting
import org.pitest.plugin.ToggleStatus
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceRegistrationTest {
    @Test
    fun `all filters are discoverable by PIT and on by default`() {
        val ours = ServiceLoader.load(MutationInterceptorFactory::class.java)
            .filter { it.javaClass.packageName == "io.github.dan323.pitkotlin" }
            .associate { it.provides().name() to it.provides() }

        assertTrue(ours.keys == setOf("fktinline", "fktintrinsics", "fktcoro", "fktunit", "fktforinline", "fktelvisdedup", "fktmessage"), ours.keys.toString())
        // FKTMESSAGE is a heuristic that can hide a test gap, so it is opt-in.
        assertEquals(setOf("fktmessage"), ours.filterValues { !it.isOnByDefault }.keys)
    }

    @Test
    fun `inline filter factory copes with options that are set or partly set`() {
        val factory = KotlinInlinedCodeFilterFactory()
        fun params(settings: Map<String, List<String>>?) = InterceptorParameters(
            settings?.let { FeatureSetting("FKTINLINE", ToggleStatus.ACTIVATE, it) },
            ReportOptions(), null, null, null, null, null,
        )
        // No settings at all, settings without the keys, and a fully specified setting.
        factory.createInterceptor(params(null))
        factory.createInterceptor(params(mapOf("other" to listOf("x"))))
        factory.createInterceptor(params(mapOf("scope" to listOf("all"), "prefixes" to listOf("arrow/"))))
        assertFailsWith<IllegalArgumentException> { factory.createInterceptor(params(mapOf("scope" to listOf("bogus")))) }
        factory.createInterceptor(params(mapOf("predicates" to listOf("drop"))))
        assertFailsWith<IllegalArgumentException> { factory.createInterceptor(params(mapOf("predicates" to listOf("bogus")))) }
    }
}
