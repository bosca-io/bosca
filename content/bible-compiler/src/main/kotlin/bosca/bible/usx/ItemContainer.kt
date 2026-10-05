package bosca.bible.usx

import bosca.bible.Reference
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.processor.HtmlContext
import bosca.bible.style.StyleReference

abstract class ItemContainer<T : Item>(
    reference: Reference? = null,
    position: Position,
) : AbstractItem(reference, position) {

    private val _items = mutableListOf<T>()

    val items: kotlin.collections.List<T>
        get() = _items

    override val htmlAttributes: Map<String, String>
        get() = emptyMap()

    open fun add(item: T) {
        _items.add(item)
    }

    override fun toComponent(context: ComponentContext) =
        ComponentContainer(
            ContainerType.DIV,
            items.mapNotNull { it.toComponent(context) },
            StyleReference(htmlClass)
        )

    override fun toHtml(context: HtmlContext) = context.render("div", this)

    override fun toString(context: StringContext): String {
        var verseContent = ""
        for (item in items) {
            verseContent += item.toString(context)
        }
        return verseContent.trim()
    }

    override fun toString(): String = toString(StringContext.default)
}