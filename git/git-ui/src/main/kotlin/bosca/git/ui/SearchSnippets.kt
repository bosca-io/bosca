package bosca.git.ui

/** Splits literal matches into escaped template text, without interpreting a query as markup. */
internal fun searchSnippetParts(text: String, query: String): List<SearchSnippetPart> {
    val term = query.trim()
    if (term.isBlank()) return listOf(SearchSnippetPart(text))
    val parts = mutableListOf<SearchSnippetPart>()
    var start = 0
    while (start < text.length) {
        val match = text.indexOf(term, start, ignoreCase = true)
        if (match < 0) {
            parts.add(SearchSnippetPart(text.substring(start)))
            break
        }
        if (match > start) parts.add(SearchSnippetPart(text.substring(start, match)))
        parts.add(SearchSnippetPart(text.substring(match, match + term.length), true))
        start = match + term.length
    }
    return parts
}
