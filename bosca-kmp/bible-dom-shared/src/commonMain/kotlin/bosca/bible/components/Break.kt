package bosca.bible.components

import bosca.bible.style.IStyle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SerialName("b")
@Serializable
class Break(override val style: IStyle? = null) : IComponent