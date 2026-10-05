package bosca.bible.usx

import bosca.bible.processor.HtmlContext
import bosca.bible.Reference

class Milestone(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : AbstractItem(reference, position),
    CharItem,
    ParagraphItem,
    BookIntroductionItem,
    BookIntroductionEndTitleItem,
    ListCharItem,
    ListItem {

    val style = attributes["STYLE"] ?: error("missing style")
    val sid = attributes["SID"] ?: error("missing sid")
    val eid = attributes["EID"] ?: error("missing eid")

    override val htmlClass = style
    override val htmlAttributes = emptyMap<String, String>()

    override fun toComponent(context: ComponentContext) = null
    override fun toHtml(context: HtmlContext) = context.render("milestone", this)
    override fun toString(context: StringContext) = ""
}
