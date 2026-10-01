package bosca.bible.components

import bosca.bible.Reference
import bosca.bible.style.IStyle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SerialName("vs")
@Serializable
class VerseStart(
    val reference: Reference,
    override val style: IStyle? = null
) : IComponent {

    override fun toString(): String {
        return "VerseStart(reference=$reference)"
    }
}