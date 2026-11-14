package io.github.dan323.pitkotlin

import org.pitest.classinfo.ClassByteArraySource
import org.pitest.classinfo.ClassName
import org.pitest.mutationtest.engine.MutationDetails
import org.pitest.mutationtest.engine.gregor.GregorMutater
import org.pitest.mutationtest.engine.gregor.MethodMutatorFactory
import java.util.Optional

/** Generates mutants of a fixture class with the given mutators and can load and run them. */
class MutantSet(private val fixture: String, factories: Collection<MethodMutatorFactory>) {
    constructor(fixture: String, factory: MethodMutatorFactory) : this(fixture, listOf(factory))

    private val source = ClassByteArraySource { name ->
        val stream = javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")
        Optional.ofNullable(stream?.use { it.readBytes() })
    }
    private val className = "fixtures.$fixture"
    val mutater = GregorMutater(source, { true }, factories)

    val mutants: List<MutationDetails> =
        mutater.findMutations(ClassName.fromString(className)).sortedBy { it.id.firstIndex }

    fun inMethod(name: String) = mutants.filter { it.method == name }

    fun bytes(mutant: MutationDetails): ByteArray = mutater.getMutation(mutant.id).bytes

    /** A new instance of the fixture class compiled from the mutated bytecode. Runs the JVM verifier. */
    fun instance(mutant: MutationDetails): Any {
        val bytes = mutater.getMutation(mutant.id).bytes
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name != className) return super.loadClass(name, resolve)
                synchronized(getClassLoadingLock(name)) {
                    return findLoadedClass(name) ?: defineClass(name, bytes, 0, bytes.size)
                }
            }
        }
        return loader.loadClass(className).getDeclaredConstructor().newInstance()
    }
}

/** Calls a public method by name, unwrapping reflection's wrapper exception. */
fun Any.call(method: String, vararg args: Any?): Any? {
    val m = javaClass.methods.first { it.name == method && it.parameterCount == args.size }
    return try {
        m.invoke(this, *args)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        throw e.targetException
    }
}
