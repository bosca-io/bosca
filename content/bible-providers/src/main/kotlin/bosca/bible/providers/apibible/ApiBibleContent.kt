package bosca.bible.providers.apibible

import bosca.bible.Reference
import bosca.bible.components.Break
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.components.IComponent
import bosca.bible.components.Text
import bosca.bible.components.VerseEnd
import bosca.bible.components.VerseStart
import bosca.bible.style.StyleReference
import java.io.IOException

/** Converts one response while retaining verse spans across paragraphs and nested styles. */
internal class ApiBibleContent(private val usfm: String) {
    private var active: Reference? = null

    fun convert(nodes: List<ApiBibleNode>): IComponent {
        val components = nodes.flatMap(::convertNode).toMutableList()
        if (active != null) components.add(VerseEnd())
        return ComponentContainer(ContainerType.DIV, components, null)
    }

    private fun convertNode(node: ApiBibleNode): List<IComponent> {
        val style = node.attrs.style?.let(::StyleReference)
        return when (node.type) {
            "text" -> buildList {
                val references = node.attrs.references.ifEmpty { listOfNotNull(node.attrs.usfm) }
                if (references.isNotEmpty()) start(reference(references), this)
                add(Text(node.text ?: invalid(), style))
            }
            "tag" -> when (node.name) {
                "verse" -> buildList {
                    val sid = node.attrs.sid?.replace(' ', '.')?.replace(':', '.')
                        ?: node.attrs.number?.let { "$usfm.$it" } ?: invalid()
                    start(reference(listOf(sid)), this)
                }
                "para", "char", "table", "row", "cell" -> {
                    val type = when (node.name) {
                        "para" -> ContainerType.PARAGRAPH
                        "char" -> ContainerType.SPAN
                        "table" -> ContainerType.TABLE
                        "row" -> ContainerType.ROW
                        else -> ContainerType.COLUMN
                    }
                    listOf(ComponentContainer(type, node.items.flatMap(::convertNode), style))
                }
                "optbreak" -> listOf(Break(style))
                else -> invalid()
            }
            else -> invalid()
        }
    }

    private fun start(reference: Reference, result: MutableList<IComponent>) {
        if (active == reference) return
        if (active != null) result.add(VerseEnd())
        result.add(VerseStart(reference))
        active = reference
    }

    private fun reference(ids: List<String>): Reference = Reference(ids.flatMap { id ->
        val parts = id.split('.')
        if (parts.size != 3 || parts[0].isBlank() || parts[1].isBlank()) invalid()
        val span = parts[2].split('-')
        when (span.size) {
            1 -> {
                if (!span[0].matches(Regex("[0-9]+[a-z]?"))) invalid()
                listOf(id)
            }
            2 -> {
                val first = span[0].toIntOrNull() ?: invalid()
                val last = span[1].toIntOrNull() ?: invalid()
                if (first > last) invalid()
                (first..last).map { "${parts[0]}.${parts[1]}.$it" }
            }
            else -> invalid()
        }
    }.distinct().joinToString("+"))

    private fun invalid(): Nothing = throw IOException("API.Bible returned invalid chapter content")
}
