package bosca.bml.annotations

/**
 * A BML render target. The same `.bml` source compiles to each.
 */
enum class BmlTarget {
    /** Full HTML with islands, JavaScript, and CSS. */
    Web,

    /** Email-safe HTML: inlined CSS, table-friendly layout, no JS/islands. */
    Email,

    /** Plain-text projection: no markup, CSS, or JS. */
    PlainText,
}
