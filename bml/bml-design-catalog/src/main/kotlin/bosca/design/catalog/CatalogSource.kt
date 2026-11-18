package bosca.design.catalog

import kotlinx.serialization.Serializable

/** Caller-owned source revisions and viewer options; preserved viewers retain their client state. */
@Serializable
data class CatalogSource(
    val id: String,
    var revision: Int = 0,
    var line: Int = 1,
    var wrapped: Boolean = false,
    var plain: Boolean = false,
    var visible: Boolean = true,
    var refreshes: Int = 0,
) {
    val content: String get() = if (revision == 0) original else "// Replacement $revision\nexport const component = \"source-view\";"

    fun refresh() { refreshes++ }
    fun showLine() { line = 40 }
    fun toggleWrap() { wrapped = !wrapped }
    fun togglePlainText() { plain = !plain }
    fun toggleVisible() { visible = !visible }

    fun replace() {
        revision++
        line = 1
    }

    companion object {
        val original: String = "// Component preview\n" + (2..64).joinToString("\n") { line ->
            "export const row$line = { name: \"Component $line\", enabled: true, description: \"A long description that can wrap inside the viewer without widening the page.\" };"
        }
    }
}
