package bosca.bible.usx

import bosca.bible.Reference
import bosca.bible.components.IComponent
import bosca.bible.processor.HtmlContext

interface Usx {

    val reference: Reference?
    val verse: String?

    val htmlClass: String
    val htmlAttributes: Map<String, String>

    fun toComponent(context: ComponentContext): IComponent?
    fun toHtml(context: HtmlContext): String
    fun toString(context: StringContext): String
}
