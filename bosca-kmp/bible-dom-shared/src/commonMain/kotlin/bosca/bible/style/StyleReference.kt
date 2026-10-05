package bosca.bible.style

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

@SerialName("sr")
@Serializable
data class StyleReference(override val id: String) : IStyle {

    override var fontFamily: String? = null
        private set

    override var fontSize: Size? = null
        private set

    override var align: TextAlign? = null
        private set

    override var fontWeight: FontWeight? = null
        private set

    override var color: String? = null
        private set

    override var margin: Margin? = null
        private set

    override var whiteSpace: Whitespace? = null
        private set

    override var verticalAlign: VerticalAlign? = null
        private set

    override var textDecoration: TextDecoration? = null
        private set

    override var textIndent: Size? = null
        private set

    override fun initialize(registry: StyleRegistry) {
        for (id in id.split(" ")) {
            val style = registry[id] ?: continue
            style.fontFamily?.let { fontFamily = it }
            style.fontSize?.let { fontSize = it }
            style.fontWeight?.let { fontWeight = it }
            style.align?.let { align = it }
            style.color?.let { color = it }
            style.margin?.let { margin = it }
            style.verticalAlign?.let { verticalAlign = it }
            style.textDecoration?.let { textDecoration = it }
            style.textIndent?.let { textIndent = it }
        }
    }

    override fun asSerializable() = Json.encodeToJsonElement(this)
}
