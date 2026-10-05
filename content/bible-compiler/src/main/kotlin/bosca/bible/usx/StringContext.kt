package bosca.bible.usx

data class StringContext(
    val includeVerseNumbers: Boolean = false,
    val includeNewLines: Boolean = false,
    val includeCrossReferences: Boolean = false,
    val includeFootNotes: Boolean = false,
) {

    companion object {
        val default = StringContext()
    }
}