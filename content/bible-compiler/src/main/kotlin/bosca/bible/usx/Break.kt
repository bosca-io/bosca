package bosca.bible.usx

import bosca.bible.processor.HtmlContext
import bosca.bible.Reference

class Break(
    reference: Reference?,
    position: Position
) : AbstractItem(reference, position),
    ParagraphItem,
    CharItem,
    TableContentItem,
    BookIntroductionEndTitleItem,
    ListCharItem,
    ListItem {

    override val htmlClass = ""
    override val htmlAttributes = emptyMap<String, String>()

    override fun toComponent(context: ComponentContext) = bosca.bible.components.Break()
    override fun toHtml(context: HtmlContext) = context.render("br", this)
    override fun toString(context: StringContext) = if (context.includeNewLines) "\r\n" else ""
}
