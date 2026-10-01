package bosca.bible.components

import bosca.bible.style.IStyle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SerialName("t")
@Serializable
class Text(
    val text: String,
    override val style: IStyle?
) : IComponent {

    override fun toString(): String {
        return "Text(text='$text')"
    }
}