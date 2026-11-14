package io.github.dan323.pitkotlin

import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.pitest.bytecode.analysis.ClassTree
import org.pitest.bytecode.analysis.MethodTree
import org.pitest.mutationtest.build.InterceptorParameters
import org.pitest.mutationtest.build.InterceptorType
import org.pitest.mutationtest.build.MutationInterceptor
import org.pitest.mutationtest.build.MutationInterceptorFactory
import org.pitest.mutationtest.engine.Mutater
import org.pitest.mutationtest.engine.MutationDetails
import org.pitest.plugin.Feature
import org.pitest.plugin.FeatureParameter

/**
 * Base class for filters that drop mutants from compiler-generated Kotlin code.
 *
 * Subclasses only decide, per mutant, whether it should be dropped.
 */
abstract class KotlinMutationFilter : MutationInterceptor {
    protected lateinit var currentClass: ClassTree
        private set

    override fun type(): InterceptorType = InterceptorType.FILTER

    override fun begin(clazz: ClassTree) {
        currentClass = clazz
    }

    override fun intercept(mutations: Collection<MutationDetails>, m: Mutater): Collection<MutationDetails> =
        mutations.filterNot(::shouldDrop)

    override fun end() {}

    protected open fun shouldDrop(mutation: MutationDetails): Boolean = false

    protected fun methodOf(mutation: MutationDetails): MethodTree? =
        currentClass.method(mutation.id.location).orElse(null)
}

/** Factory base: one subclass per filter, each registered in META-INF/services. */
abstract class KotlinFilterFactory(
    private val featureName: String,
    private val description: String,
    private val create: (InterceptorParameters) -> MutationInterceptor,
    private val parameters: List<FeatureParameter> = emptyList(),
) : MutationInterceptorFactory {
    override fun createInterceptor(params: InterceptorParameters): MutationInterceptor = create(params)

    override fun provides(): Feature =
        parameters.fold(Feature.named(featureName).withOnByDefault(true).withDescription(description)) { f, p ->
            f.withParameter(p)
        }

    override fun description(): String = description
}

/** Real bytecode instructions only: skips labels, line numbers and frames (opcode -1). */
internal fun List<AbstractInsnNode>.realBefore(index: Int): AbstractInsnNode? {
    var i = index - 1
    while (i >= 0) {
        if (this[i].opcode >= 0) return this[i]
        i--
    }
    return null
}

internal fun List<AbstractInsnNode>.realAfter(index: Int): AbstractInsnNode? {
    var i = index + 1
    while (i < size) {
        if (this[i].opcode >= 0) return this[i]
        i++
    }
    return null
}

internal fun AbstractInsnNode?.isStaticCall(owner: String, vararg names: String): Boolean =
    this is MethodInsnNode && this.owner == owner && this.name in names
