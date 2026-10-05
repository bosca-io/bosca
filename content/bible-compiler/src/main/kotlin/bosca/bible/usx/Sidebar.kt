package bosca.bible.usx

import bosca.bible.Reference

/**
 * Marker interface for items that can appear as children within a [Sidebar] container.
 * Implemented by [Paragraph], [Table], [bosca.bible.usx.List], [CrossReference], and [Footnote].
 */
interface SidebarItem : Item

/**
 * Represents a USX sidebar element (`<sidebar>`), used in study Bibles to provide
 * supplementary content such as commentary, study notes, or topical articles alongside
 * the main scripture text.
 *
 * @property style The sidebar style attribute, defaults to `"esb"` (extended study Bible).
 * @property category An optional category for classifying the sidebar content.
 */
class Sidebar(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<SidebarItem>(reference, position),
    RootItem,
    ChapterItem {

    val style = attributes["STYLE"] ?: "esb"
    val category = attributes["CATEGORY"]

    override val htmlClass = style
}
