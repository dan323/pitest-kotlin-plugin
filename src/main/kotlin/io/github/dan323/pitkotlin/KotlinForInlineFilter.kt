package io.github.dan323.pitkotlin

import org.pitest.mutationtest.engine.MutationDetails

/**
 * Drops every mutant in a `name$$forInline` method.
 *
 * For an `inline suspend` function the compiler emits two bodies: the real state machine, and a
 * `$$forInline` copy that exists only so call sites can inline it. The copy is never called, so
 * its mutants can never be covered, let alone killed.
 */
class KotlinForInlineFilter : KotlinMutationFilter() {
    override fun shouldDrop(mutation: MutationDetails): Boolean =
        mutation.id.location.methodName.endsWith(SUFFIX)

    private companion object {
        const val SUFFIX = "\$\$forInline"
    }
}

class KotlinForInlineFilterFactory : KotlinFilterFactory(
    "FKTFORINLINE",
    "Filters mutations in the never-executed \$\$forInline copies of inline suspend functions",
    { KotlinForInlineFilter() },
)
