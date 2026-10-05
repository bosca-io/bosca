package bosca.bible.components

import bosca.bible.style.IStyle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SerialName("ve")
@Serializable
class VerseEnd : IComponent {

    override val style: IStyle? = null

    override fun toString(): String {
        return "VerseEnd()"
    }
}