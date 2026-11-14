package io.github.dan323.pitkotlin

import org.pitest.bytecode.analysis.ClassTree
import org.pitest.mutationtest.build.InterceptorType
import org.pitest.mutationtest.build.MutationInterceptor
import org.pitest.mutationtest.engine.Mutater
import org.pitest.mutationtest.engine.MutationDetails

/**
 * Drops PIT's own conditional mutants on an elvis jump when this plugin's elvis mutants already
 * cover the same ground:
 *
 *  - `REMOVE_CONDITIONALS_EQUAL_ELSE` (in `DEFAULTS`) produces the same mutant as `KOTLIN_ELVIS_NEVER_DEFAULT`
 *    (the jump is always taken),
 *  - `REMOVE_CONDITIONALS_EQUAL_IF` (in `STRONGER`) the same as `KOTLIN_ELVIS_ALWAYS_DEFAULT` and
 *    `KOTLIN_SAFECALL_NEVER_NULL` (the jump is never taken),
 *  - `NEGATE_CONDITIONALS` (in `ALL`) is caught by either, so it needs both present.
 *
 * Each built-in mutant is only dropped when its replacement is present at the same instruction, so
 * enabling just one of the elvis mutators never loses coverage. Without elvis mutants nothing changes.
 */
class KotlinElvisDuplicateFilter : MutationInterceptor {
    override fun type(): InterceptorType = InterceptorType.FILTER

    override fun begin(clazz: ClassTree) {}

    override fun end() {}

    override fun intercept(mutations: Collection<MutationDetails>, m: Mutater): Collection<MutationDetails> {
        val present = mutations.mapNotNull { d -> elvisKind(d)?.let { site(d) to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }
        if (present.isEmpty()) return mutations
        return mutations.filterNot { d -> present[site(d)]?.let { isRedundant(d.mutator, it) } == true }
    }

    private fun site(d: MutationDetails) = d.id.location to d.id.firstIndex

    private fun elvisKind(d: MutationDetails): Kind? = when {
        d.mutator == KotlinElvisAlwaysDefaultMutator::class.java.name -> Kind.NEVER_JUMP
        d.mutator == KotlinSafeCallNeverNullMutator::class.java.name -> Kind.NEVER_JUMP
        d.mutator == KotlinElvisNeverDefaultMutator::class.java.name -> Kind.ALWAYS_JUMP
        else -> null
    }

    private fun isRedundant(mutator: String, available: Set<Kind>): Boolean = when {
        mutator.endsWith("RemoveConditionalMutator_EQUAL_ELSE") -> Kind.ALWAYS_JUMP in available
        mutator.endsWith("RemoveConditionalMutator_EQUAL_IF") -> Kind.NEVER_JUMP in available
        mutator.endsWith("NegateConditionalsMutator") -> available.size == 2
        else -> false
    }

    private enum class Kind { NEVER_JUMP, ALWAYS_JUMP }
}

class KotlinElvisDuplicateFilterFactory : KotlinFilterFactory(
    "FKTELVISDEDUP",
    "Filters PIT's conditional mutants on elvis and safe-call jumps that the Kotlin mutators duplicate or subsume",
    { KotlinElvisDuplicateFilter() },
)
