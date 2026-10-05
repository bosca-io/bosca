package bosca.bible.usx

import bosca.bible.Reference

interface RootItem : Item

class Root(
    attributes: Attributes,
    reference: Reference?,
    position: Position
) : ItemContainer<RootItem>(reference, position) {

    override val htmlClass: String = ""
}
