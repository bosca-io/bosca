package bosca.bml.library

import bosca.bml.parser.Span

/** A literal library import; a null [alias] exposes exports as ordinary, unprefixed tags. */
data class BmlImport(val library: String, val alias: String?, val source: String, val span: Span)
