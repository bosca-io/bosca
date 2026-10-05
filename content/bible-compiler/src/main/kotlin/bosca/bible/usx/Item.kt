package bosca.bible.usx

import bosca.bible.Reference

interface Item : Usx {

    val position: Position
}

abstract class AbstractItem(
    override val reference: Reference?,
    override val position: Position
) : Item {

    override val verse: String?
        get() = reference?.number

    override fun toString() = toString(StringContext.default)
}