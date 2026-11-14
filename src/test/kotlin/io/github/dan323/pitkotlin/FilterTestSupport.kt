package io.github.dan323.pitkotlin

import org.pitest.bytecode.analysis.ClassTree
import org.pitest.classinfo.ClassByteArraySource
import org.pitest.classinfo.ClassName
import org.pitest.mutationtest.build.MutationInterceptor
import org.pitest.mutationtest.engine.MutationDetails
import org.pitest.mutationtest.engine.gregor.GregorMutater
import org.pitest.mutationtest.engine.gregor.config.Mutator
import java.util.Optional

/** Mutants of a fixture class split into what a filter keeps and drops. */
class FilterResult(val kept: List<MutationDetails>, val dropped: List<MutationDetails>) {
    fun droppedIn(method: String) = dropped.filter { it.method == method }
    fun keptIn(method: String) = kept.filter { it.method == method }
}

object FilterTestSupport {
    private val source = ClassByteArraySource { name ->
        val stream = javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")
        Optional.ofNullable(stream?.use { it.readBytes() })
    }

    /**
     * Runs PIT's default mutators on the fixture and applies [filter].
     * Line-0 mutants are discarded first, mirroring PIT's built-in Kotlin filter.
     */
    fun run(fixture: String, filter: MutationInterceptor): FilterResult {
        val name = ClassName.fromString("fixtures.$fixture")
        val tree = ClassTree.fromBytes(source.getBytes(name.asInternalName()).orElseThrow())
        val mutater = GregorMutater(source, { true }, Mutator.newDefaults())
        val all = mutater.findMutations(name).filter { it.lineNumber != 0 }

        filter.begin(tree)
        val kept = filter.intercept(all, mutater).toList()
        filter.end()
        return FilterResult(kept, all - kept.toSet())
    }
}
