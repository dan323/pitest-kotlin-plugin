package io.github.dan323.pitkotlin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.pitest.bytecode.analysis.ClassTree
import org.pitest.bytecode.analysis.MethodTree
import org.pitest.mutationtest.engine.Location
import org.pitest.mutationtest.engine.Mutater
import org.pitest.mutationtest.build.InterceptorParameters
import org.pitest.mutationtest.engine.MutationDetails
import org.pitest.plugin.FeatureParameter

/**
 * Drops mutants located in code that the Kotlin compiler copied into this class by inlining
 * (`map`, `filter`, `let`, ...).
 *
 * Inlined bodies are attributed to synthetic line numbers past the end of the file. The class's
 * SMAP (`SourceDebugExtension`) tells which output lines are real source lines, and which class
 * each inlined range came from. Lambdas passed to inline functions stay on their real lines and
 * are still mutated.
 *
 * By default only code inlined from the Kotlin standard library (`kotlin/`, `kotlinx/`) is
 * dropped, because the project's own inline functions hold logic worth mutating, and reified ones
 * are otherwise only ever executed through their inlined copies.
 *
 * The one exception is the jump that consumes an inlined lambda's boolean result (`filter { }`, `any { }`,
 * `all { }`, `count { }`, ...): mutating it asks whether your predicate matters, so those mutants are kept,
 * reported on the lambda's line as `filter{} predicate: ...`. `predicates[drop]` turns this off.
 *
 * Options (`+FKTINLINE(scope[all])`):
 *  - `scope`: `stdlib` (default) or `all` to also drop the project's own and other libraries' inline code.
 *  - `prefixes`: extra internal-name prefixes treated as library code, e.g. `arrow/`.
 *  - `predicates`: `keep` (default) or `drop` for the predicate jumps described above.
 */
class KotlinInlinedCodeFilter(
    private val dropAll: Boolean = false,
    private val libraryPrefixes: List<String> = DEFAULT_PREFIXES,
    private val keepPredicates: Boolean = true,
) : KotlinMutationFilter() {
    private var inlinedRanges: List<IntRange> = emptyList()
    private var predicateJumps: Map<Location, Map<Int, PredicateJump>> = emptyMap()

    override fun begin(clazz: ClassTree) {
        super.begin(clazz)
        inlinedRanges = Smap.inlinedRanges(clazz.rawNode().sourceDebug)
            .filter { dropAll || libraryPrefixes.any { prefix -> it.origin.startsWith(prefix) } }
            .map { it.lines }
        predicateJumps = if (keepPredicates && inlinedRanges.isNotEmpty()) {
            clazz.methods().associate { it.asLocation() to PredicateJumps.find(it) }.filterValues { it.isNotEmpty() }
        } else {
            emptyMap()
        }
    }

    override fun intercept(mutations: Collection<MutationDetails>, m: Mutater): Collection<MutationDetails> =
        mutations.mapNotNull { mutation ->
            if (inlinedRanges.none { mutation.lineNumber in it }) return@mapNotNull mutation
            val jump = predicateJumps[mutation.id.location]?.get(mutation.instructionIndex) ?: return@mapNotNull null
            jump.relocate(mutation)
        }

    companion object {
        val DEFAULT_PREFIXES = listOf("kotlin/", "kotlinx/")
    }
}

/** The jump that consumes the boolean result of an inlined lambda, and where that lambda lives in the source. */
internal class PredicateJump(val function: String, val line: Int) {
    /** Reports the mutant on the lambda's own line, since the jump's line is a synthetic one past the end of the file. */
    fun relocate(mutation: MutationDetails): MutationDetails =
        MutationDetails(
            mutation.id,
            mutation.filename,
            "$function{} predicate: ${mutation.description}",
            line,
            mutation.blocks,
        )
}

/**
 * Finds `filter { }`, `any { }`, `all { }`, `count { }`, `first { }`, `takeWhile { }`... decisions.
 *
 * The compiler marks each inlined lambda with a local variable `$i$a$-<function>-...` whose range ends
 * where the lambda has produced its result. The first real instruction after that range is the `ifeq` or
 * `ifne` in the stdlib code that acts on the result, so mutating it means "the predicate said the opposite",
 * which is the question these functions exist to ask. It lies in the inlined (synthetic) lines.
 */
internal object PredicateJumps {
    fun find(method: MethodTree): Map<Int, PredicateJump> {
        val node = method.rawNode()
        val insns = method.instructions()
        val result = mutableMapOf<Int, PredicateJump>()
        for (variable in node.localVariables.orEmpty()) {
            if (!variable.name.startsWith("\$i\$a\$-")) continue
            var i = insns.indexOf(variable.end)
            while (i in insns.indices && insns[i].opcode < 0) i++
            val jump = insns.getOrNull(i) as? JumpInsnNode ?: continue
            if (jump.opcode != Opcodes.IFEQ && jump.opcode != Opcodes.IFNE) continue
            val function = variable.name.removePrefix("\$i\$a\$-").substringBefore('-')
            result[i] = PredicateJump(function, lambdaLine(insns, insns.indexOf(variable.start)))
        }
        return result
    }

    private fun lambdaLine(insns: List<AbstractInsnNode>, from: Int): Int {
        for (i in from until insns.size) (insns[i] as? LineNumberNode)?.let { return it.line }
        return 0
    }
}

class KotlinInlinedCodeFilterFactory : KotlinFilterFactory(
    "FKTINLINE",
    "Filters mutations in code inlined from the Kotlin standard library (scope[all] for any inlined code)",
    ::create,
    listOf(SCOPE, PREFIXES, PREDICATES),
) {
    private companion object {
        val SCOPE = FeatureParameter.named("scope")
            .withDescription("stdlib (default): only kotlin/ and kotlinx/ code. all: any inlined code")
        val PREFIXES = FeatureParameter.named("prefixes")
            .withDescription("Extra internal-name prefixes (e.g. arrow/) treated as library code")

        val PREDICATES = FeatureParameter.named("predicates")
            .withDescription("keep (default): keep mutants on the result of inlined predicate lambdas. drop: drop them too")

        fun create(params: InterceptorParameters): KotlinInlinedCodeFilter {
            val scope = params.getString(SCOPE)?.orElse("stdlib") ?: "stdlib"
            require(scope == "stdlib" || scope == "all") { "FKTINLINE scope must be stdlib or all, was '$scope'" }
            // PIT returns null (not an empty list) when other options are set but this one is absent.
            val extra: List<String>? = params.getList(PREFIXES)
            val prefixes = KotlinInlinedCodeFilter.DEFAULT_PREFIXES + extra.orEmpty()
            val predicates = params.getString(PREDICATES)?.orElse("keep") ?: "keep"
            require(predicates == "keep" || predicates == "drop") { "FKTINLINE predicates must be keep or drop, was '$predicates'" }
            return KotlinInlinedCodeFilter(dropAll = scope == "all", libraryPrefixes = prefixes, keepPredicates = predicates == "keep")
        }
    }
}

/** A range of output lines that does not map onto the file's own lines, with the class it came from. */
internal data class InlinedRange(val lines: IntRange, val origin: String)

/** Minimal parser for the "Kotlin" stratum of a JSR-045 SMAP. */
internal object Smap {
    private val entry = Regex("""(\d+)(?:#(\d+))?(?:,(\d+))?:(\d+)(?:,(\d+))?""")

    fun inlinedRanges(smap: String?): List<InlinedRange> {
        if (smap.isNullOrBlank()) return emptyList()
        val lines = smap.lines()
        val files = fileOrigins(lines)
        val start = lines.indexOf("*L")
        if (start < 0) return emptyList()

        val ranges = mutableListOf<InlinedRange>()
        var fileId = 1
        for (line in lines.drop(start + 1)) {
            if (line.startsWith("*")) break
            val m = entry.matchEntire(line.trim()) ?: continue
            val (input, file, repeat, output, increment) = m.destructured
            if (file.isNotEmpty()) fileId = file.toInt()
            val count = if (repeat.isEmpty()) 1 else repeat.toInt()
            val step = if (increment.isEmpty()) 1 else increment.toInt()
            val outStart = output.toInt()
            val isOwn = fileId == 1 && input.toInt() == outStart && step == 1
            if (!isOwn) ranges += InlinedRange(outStart until outStart + count * step, files[fileId].orEmpty())
        }
        return ranges
    }

    /** `+ <id> <name>` is followed by the internal name of the class that declared the code. */
    private fun fileOrigins(lines: List<String>): Map<Int, String> {
        val start = lines.indexOf("*F")
        if (start < 0) return emptyMap()
        val origins = mutableMapOf<Int, String>()
        var i = start + 1
        while (i < lines.size && !lines[i].startsWith("*")) {
            val m = Regex("""\+ (\d+) (.*)""").matchEntire(lines[i])
            if (m != null) {
                origins[m.groupValues[1].toInt()] = lines.getOrNull(i + 1)?.takeUnless { it.startsWith("+") || it.startsWith("*") }
                    ?: m.groupValues[2]
                i += 2
            } else {
                i++
            }
        }
        return origins
    }
}
