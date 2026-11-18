package bosca.bml.render

/**
 * Combines forwarded component attributes with the receiving element's explicit attributes.
 * Explicit values take precedence, including null/false; classes from both maps are combined.
 * Values remain unescaped until [HtmlWriter] writes them.
 */
fun mergeAttributes(forwarded: Map<String, Any?>, explicit: Map<String, Any?>): Map<String, Any?> {
    if (forwarded.isEmpty()) return explicit
    if (explicit.isEmpty()) return forwarded
    val merged = forwarded + explicit
    if ("class" !in forwarded || "class" !in explicit) return merged
    val classes = listOf(explicit["class"], forwarded["class"])
        .filter { it != null && it != false }
        .map { it.toString().trim() }
        .filter(String::isNotEmpty)
        .joinToString(" ")
    return merged + ("class" to classes.takeIf(String::isNotEmpty))
}
