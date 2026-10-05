package bosca.bible.processor

import bosca.bible.style.*

object StyleProcessor {

    @Suppress("UNCHECKED_CAST")
    fun process(data: ByteArray): List<IStyle> {
        val styles = mutableListOf<IStyle>()
        val stylesheet = XMLProcessor.process(data)["stylesheet"] as Map<String, Any>

        stylesheet["property"]?.let {
            styles.add(toStyle(toStyleProperties("", it as List<Map<String, Any>>)))
        }

        stylesheet["style"]?.let {
            for (style in (it as List<Map<String, Any>>)) {
                val id = style["id"] as? String ?: continue
                when (val properties = style["property"]) {
                    is List<*> -> {
                        styles.add(toStyle(toStyleProperties(id, properties as List<Map<String, Any>>)))
                    }
                    is Map<*, *> -> {
                        styles.add(toStyle(toStyleProperties(id, listOf(properties as Map<String, Any>))))
                    }
                }
            }
        }

        return styles
    }

    private fun toStyleProperties(id: String, data: List<Map<String, Any>>): Map<String, String> {
        val properties = mutableMapOf<String, String>()
        properties["id"] = id
        for (property in data) {
            val name = property["name"] as String
            val value = property["#text"] as String
            properties[name] = value
            property["unit"]?.let {
                properties["$name.unit"] = it.toString()
            }
        }
        return properties
    }

    private fun toSize(properties: Map<String, String>, name: String): Size? {
        val size = properties[name] ?: return null
        val unit = properties["$name.unit"] ?: return null
        return Size(
            size.toFloat(), when (unit) {
                "pt" -> SizeUnit.POINT
                "in" -> SizeUnit.INCH
                "%" -> SizeUnit.PERCENT
                else -> return null
            }
        )
    }

    private fun toMargin(properties: Map<String, String>): Margin? {
        val top = toSize(properties, "margin-top")
        val bottom = toSize(properties, "margin-bottom")
        val left = toSize(properties, "margin-left")
        val right = toSize(properties, "margin-right")
        if (top != null || bottom != null || left != null || right != null) {
            return Margin(
                top = top,
                bottom = bottom,
                left = left,
                right = right
            )
        }
        return null
    }

    private fun toTextAlign(property: Map<String, String>): TextAlign? {
        val value = property["text-align"] ?: return null
        return when (value) {
            "left" -> TextAlign.LEFT
            "center" -> TextAlign.CENTER
            "right" -> TextAlign.RIGHT
            "justify" -> TextAlign.JUSTIFY
            else -> TODO("text-align: $value")
        }
    }

    private fun toTextDecoration(property: Map<String, String>): TextDecoration? {
        val value = property["text-decoration"] ?: return null
        return when (value) {
            "underline" -> TextDecoration.UNDERLINE
            else -> TODO("text-decoration: $value")
        }
    }

    private fun toVerticalAlign(property: Map<String, String>): VerticalAlign? {
        val value = property["vertical-align"] ?: return null
        return when (value) {
            "text-top" -> VerticalAlign.TEXT_TOP
            else -> TODO("vertical-align: $value")
        }
    }

    private fun toFontWeight(property: Map<String, String>): FontWeight? {
        val value = property["font-weight"] ?: return null
        return when (value) {
            "bold" -> FontWeight.BOLD
            "italic" -> FontWeight.ITALIC
            "normal" -> FontWeight.NORMAL
            else -> TODO("font-weight: $value")
        }
    }

    private fun toWhitespace(property: Map<String, String>): Whitespace? {
        val value = property["white-space"] ?: return null
        return when (value) {
            "nowrap" -> Whitespace.NOWRAP
            else -> TODO("whitespace: $value")
        }
    }

    private fun toStyle(properties: Map<String, String>): IStyle {
        return Style(
            id = properties["id"] ?: error("missing id"),
            fontFamily = properties["font-family"],
            fontSize = toSize(properties, "font-size"),
            align = toTextAlign(properties),
            fontWeight = toFontWeight(properties),
            color = properties["color"],
            margin = toMargin(properties),
            whiteSpace = toWhitespace(properties),
            verticalAlign = toVerticalAlign(properties),
            textDecoration = toTextDecoration(properties),
            textIndent = toSize(properties, "text-indent"),
        )
    }
}