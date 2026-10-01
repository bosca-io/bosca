package bosca.bible.components

import bosca.bible.style.IStyle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ContainerType {
    DIV,
    SPAN,
    PARAGRAPH,
    TABLE,
    ROW,
    COLUMN,
}

@SerialName("cc")
@Serializable
class ComponentContainer(
    val type: ContainerType,
    val components: List<IComponent>,
    override val style: IStyle?
) : IComponent {

    override fun toString(): String {
        return "ComponentContainer(type=$type, components=$components)"
    }
}