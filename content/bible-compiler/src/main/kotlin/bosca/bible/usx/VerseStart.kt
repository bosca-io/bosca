package bosca.bible.usx

import bosca.bible.processor.HtmlContext
import bosca.bible.Reference
import bosca.bible.VerseStartStyle

class VerseStart(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : AbstractItem(reference, position),
    ParagraphItem,
    ListItem,
    RowItem,
    TableContentItem {

    val style: VerseStartStyle = VerseStartStyle.valueOf(attributes["STYLE"] ?: error("missing style"))
    val number: String = attributes["NUMBER"] ?: error("missing number")
    val altNumber: String? = attributes["ALTNUMBER"]?.takeIf { it.isNotEmpty() }
    val pubNumber: String? = attributes["PUBNUMBER"]?.takeIf { it.isNotEmpty() }
    val sid: String = attributes["SID"] ?: error("missing sid")

    override val htmlClass: String
        get() = style.name

    override val htmlAttributes: Map<String, String>
        get() = mapOf(
            "data-usfm" to (reference?.usfm ?: ""),
            "data-verse" to (verse ?: error("missing verse"))
        )

    override fun toComponent(context: ComponentContext) =
        bosca.bible.components.VerseStart(reference ?: error("missing reference"))

    override fun toHtml(context: HtmlContext): String {
        if (context.includeVerseNumbers) return context.render("span", this, this.number)
        return ""
    }

    override fun toString(context: StringContext): String {
        if (context.includeVerseNumbers) return "$number. "
        return ""
    }
}
