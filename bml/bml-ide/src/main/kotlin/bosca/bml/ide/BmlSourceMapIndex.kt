package bosca.bml.ide

/**
 * Reads a `<Object>.kt.map` sidecar emitted by the BML compiler — JSON of the form
 * `{"source":"…","mappings":[[generatedLine,sourceLine],…]}` — and offers bidirectional line
 * lookup between a generated `.kt` and its `.bml` source. This is the data a debugger
 * `PositionManager` needs to place and resolve `.bml` breakpoints.
 *
 * Pure logic, no IDE/JDI dependencies, so the core of the (otherwise debug-session-only)
 * breakpoint feature is unit-testable. Kept independent of `bml-compiler`'s `BmlSourceMap`
 * because `bml-ide` is a standalone build that can't depend on the composite modules.
 */
class BmlSourceMapIndex private constructor(
    /** The `.bml` source path the generated file came from (as written by the compiler). */
    val sourcePath: String,
    private val generatedToSource: Map<Int, Int>,
) {
    /** Resolve a stop: the `.bml` line a generated `.kt` line came from, or null if unmapped. */
    fun bmlLineFor(generatedLine: Int): Int? = generatedToSource[generatedLine]

    /** Set a breakpoint: the generated `.kt` line(s) a `.bml` line produced (ascending). */
    fun generatedLinesFor(bmlLine: Int): List<Int> =
        generatedToSource.filterValues { it == bmlLine }.keys.sorted()

    /** The first generated `.kt` line for a `.bml` line (where a breakpoint request is placed). */
    fun firstGeneratedLineFor(bmlLine: Int): Int? = generatedLinesFor(bmlLine).firstOrNull()

    companion object {
        fun parse(json: String): BmlSourceMapIndex {
            val source = Regex("\"source\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
                ?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                ?: ""
            val mappings = LinkedHashMap<Int, Int>()
            Regex("\\[(\\d+),(\\d+)]").findAll(json).forEach {
                mappings[it.groupValues[1].toInt()] = it.groupValues[2].toInt()
            }
            return BmlSourceMapIndex(source, mappings)
        }
    }
}
