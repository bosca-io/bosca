package bosca.bible.usx

import bosca.bible.processor.HtmlContext
import bosca.bible.Reference
import bosca.bible.style.StyleReference

class Text(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : AbstractItem(reference, position),
    RootItem,
    ParagraphItem,
    BookIntroductionEndTitleItem,
    ListItem,
    ChapterItem,
    BookTitleItem,
    FootnoteItem,
    CrossReferenceItem,
    CrossReferenceCharItem,
    CharItem,
    TableContentItem,
    BookIntroductionItem,
    FootnoteCharItem,
    IntroCharItem,
    ListCharItem {

    var text = ""

    override val htmlClass = "verse"

    override val htmlAttributes: Map<String, String>
        get() {
            val verse = verse ?: return emptyMap()
            return reference?.let {
                mapOf(
                    "data-usfm" to it.usfm,
                    "data-verse" to verse
                )
            } ?: mapOf("data-verse" to verse)
        }

    override fun toComponent(context: ComponentContext) = bosca.bible.components.Text(
        text,
        StyleReference(htmlClass)
    )

    override fun toHtml(context: HtmlContext): String {
        if (text.trim().isEmpty()) return ""
        return context.render("span", this, text)
    }

    override fun toString(context: StringContext): String {
        if (!context.includeNewLines) return text.replace(Regex("\r?\n"), "")
        return text
    }
}
