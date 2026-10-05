package bosca.ai.kit.tools.bible

import bosca.bible.components.Break
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.components.IComponent
import bosca.bible.components.Text
import bosca.bible.components.VerseEnd
import bosca.bible.components.VerseStart

/**
 * Renders an [IComponent] tree to plain text suitable for LLM consumption.
 * Verse boundaries are marked with bracketed verse numbers: [1], [2], etc.
 */
object ComponentTextRenderer {

    fun render(component: IComponent): String {
        val sb = StringBuilder()
        renderComponent(component, sb)
        return sb.toString().trim()
    }

    private fun renderComponent(component: IComponent, sb: StringBuilder) {
        when (component) {
            is VerseStart -> {
                val verseNumber = component.reference.number
                if (verseNumber.isNotEmpty()) {
                    if (sb.isNotEmpty() && sb.last() != '\n' && sb.last() != ' ') {
                        sb.append(' ')
                    }
                    sb.append('[').append(verseNumber).append("] ")
                }
            }
            is VerseEnd -> {}
            is Text -> sb.append(component.text)
            is Break -> sb.append('\n')
            is ComponentContainer -> {
                for (child in component.components) {
                    renderComponent(child, sb)
                }
                if (component.type == ContainerType.PARAGRAPH) {
                    sb.append('\n')
                }
            }
        }
    }
}
