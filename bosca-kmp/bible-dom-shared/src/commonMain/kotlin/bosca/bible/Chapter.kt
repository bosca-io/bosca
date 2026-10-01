package bosca.bible

import bosca.bible.components.IComponent
import bosca.bible.components.filter
import kotlinx.serialization.Serializable

interface IChapter {

    val reference: Reference

    operator fun get(reference: Reference): IComponent?

    fun asSerializable() = Chapter(reference, get(reference))
}

@Serializable
data class Chapter(
    override val reference: Reference,
    private val component: IComponent?
) : IChapter {

    override fun get(reference: Reference): IComponent? {
        if (this.reference == reference) return component
        return component?.filter(reference)
    }
}