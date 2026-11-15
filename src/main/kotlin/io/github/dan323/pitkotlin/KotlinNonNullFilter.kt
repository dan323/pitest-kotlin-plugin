package io.github.dan323.pitkotlin

import org.pitest.bytecode.analysis.ClassTree
import org.pitest.classinfo.ClassByteArraySource
import org.pitest.mutationtest.build.InterceptorParameters
import org.pitest.mutationtest.engine.MutationDetails

/**
 * Drops the "never null" mutants on a value that cannot be null: `a?.b()?.c()` links after a `@NotNull`
 * call, `x?.let { f(it) } ?: y` where `f` never returns null, `"..." + s` results, and so on.
 *
 * Kotlin emits `dup; ifnull` / `dup; ifnonnull` even when the value is non-null (the type of a safe-call
 * chain is nullable), and the null path is already handled by an earlier jump to the same exit. The
 * mutant that forces the non-null path ([NullSites]) then runs the very code it was already running.
 *
 * Sound, so on by default: see [NonNull] for what counts as proof. Anything it cannot prove is kept.
 */
class KotlinNonNullFilter(private val source: ClassByteArraySource?) : KotlinMutationFilter() {
    private var nonNull = NonNull(source, null)

    override fun begin(clazz: ClassTree) {
        super.begin(clazz)
        nonNull = NonNull(source, clazz)
    }

    override fun shouldDrop(mutation: MutationDetails): Boolean {
        val method = methodOf(mutation) ?: return false
        val insns = method.instructions()
        val dup = NullSites.neverNullDup(mutation, insns) ?: return false
        return nonNull.valueBefore(method.rawNode(), insns, dup)
    }
}

class KotlinNonNullFilterFactory : KotlinFilterFactory(
    "FKTNONNULL",
    "Filters 'never null' elvis and safe-call mutants on values that cannot be null (@NotNull calls, string results)",
    { p: InterceptorParameters -> KotlinNonNullFilter(p.source()) },
)
