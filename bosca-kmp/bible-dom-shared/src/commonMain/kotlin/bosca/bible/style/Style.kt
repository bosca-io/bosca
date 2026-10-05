package bosca.bible.style

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

interface IStyle {
    val id: String
    val fontFamily: String?
    val fontSize: Size?
    val align: TextAlign?
    val fontWeight: FontWeight?
    val color: String?
    val margin: Margin?
    val whiteSpace: Whitespace?
    val verticalAlign: VerticalAlign?
    val textDecoration: TextDecoration?
    val textIndent: Size?

    fun initialize(registry: StyleRegistry)

    fun asSerializable(): JsonElement
}

@SerialName("s")
@Serializable
data class Style(
    override val id: String,
    override val fontFamily: String? = null,
    override val fontSize: Size? = null,
    override val align: TextAlign? = null,
    override val fontWeight: FontWeight? = null,
    override val color: String? = null,
    override val margin: Margin? = null,
    override val whiteSpace: Whitespace? = null,
    override val verticalAlign: VerticalAlign? = null,
    override val textDecoration: TextDecoration? = null,
    override val textIndent: Size? = null
) : IStyle {

    override fun initialize(registry: StyleRegistry) {}

    override fun toString(): String {
        return "Style(id='$id', fontFamily=$fontFamily, fontSize=$fontSize, align=$align, fontWeight=$fontWeight, color=$color, margin=$margin, whiteSpace=$whiteSpace, verticalAlign=$verticalAlign, textDecoration=$textDecoration, textIndent=$textIndent)"
    }

    override fun asSerializable() = Json.encodeToJsonElement(this)
}
