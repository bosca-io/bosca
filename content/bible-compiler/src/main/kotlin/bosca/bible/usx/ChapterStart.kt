package bosca.bible.usx

import bosca.bible.processor.HtmlContext
import bosca.bible.Reference

class ChapterStart(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : AbstractItem(reference, position),
    RootItem {

    val number: String = attributes["NUMBER"] ?: error("missing number")
    val altNumber: String? = attributes["ALTNUMBER"]
    val pubNumber: String? = attributes["PUBNUMBER"]
    val sid: String = attributes["SID"] ?: error("missing sid")

    override val htmlClass: String = ""
    override val htmlAttributes = emptyMap<String, String>()

    override fun toComponent(context: ComponentContext) = null
    override fun toHtml(context: HtmlContext) = ""
    override fun toString(context: StringContext) = ""
}
