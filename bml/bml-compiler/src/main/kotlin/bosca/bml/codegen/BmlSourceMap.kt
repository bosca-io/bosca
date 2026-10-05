package bosca.bml.codegen

/** One generated-line → source-line mapping (both 1-based). */
data class BmlLineMapping(val generatedLine: Int, val sourceLine: Int)

/**
 * A `.bml` ↔ generated-`.kt` line map (foundation for `.bml` breakpoints).
 * Maps 1-based generated Kotlin line numbers back to 1-based `.bml` source lines, so the
 * IntelliJ plugin can place breakpoints in `.bml` and a debugger can resolve a generated-`.kt`
 * stop to its originating `.bml` position. Written as a `<Object>.kt.map` sidecar by the
 * project generator.
 *
 * Dependency-free on purpose: `bml-compiler`'s codegen has no kotlinx.serialization dependency,
 * and the emitted JSON is trivial.
 */
data class BmlSourceMap(
    val sourcePath: String,
    val mappings: List<BmlLineMapping>,
) {
    /** The `.bml` source line a generated line came from, or null if unmapped. */
    fun sourceLineFor(generatedLine: Int): Int? =
        mappings.firstOrNull { it.generatedLine == generatedLine }?.sourceLine

    /** Compact JSON: `{"source":"…","mappings":[[gen,src],…]}`. */
    fun toJson(): String {
        val pairs = mappings.joinToString(",") { "[${it.generatedLine},${it.sourceLine}]" }
        return "{\"source\":${jsonString(sourcePath)},\"mappings\":[$pairs]}"
    }

    private fun jsonString(s: String): String {
        val out = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    companion object {
        /** Parse the compact JSON form back into a [BmlSourceMap] (used by the IDE consumer/tests). */
        fun fromJson(json: String): BmlSourceMap {
            val source = Regex("\"source\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
                ?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: ""
            val mappings = Regex("\\[(\\d+),(\\d+)]").findAll(json)
                .map { BmlLineMapping(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
                .toList()
            return BmlSourceMap(source, mappings)
        }
    }
}
