package bosca.bml.ide

import com.intellij.openapi.util.TextRange
import com.intellij.psi.AbstractElementManipulator
import com.intellij.psi.impl.source.tree.LeafElement

/**
 * Tells the platform which sub-range of an injection host actually holds injectable code, and
 * applies edits coming back from the injected fragment.
 *
 * - Interpolation `{ expr }` → range excludes the surrounding braces.
 * - Script bodies → the whole element is code.
 */
class BmlHostManipulator : AbstractElementManipulator<BmlInjectionHost>() {

    override fun getRangeInElement(element: BmlInjectionHost): TextRange {
        // Interpolation `{ … }` and attribute values `"…"` both inject their inner text
        // (the delimiters — braces or quotes — are the first and last character).
        val type = element.node.elementType
        if (type == BmlElementTypes.INTERPOLATION_HOST || type == BmlElementTypes.ATTR_VALUE_HOST) {
            val len = element.textLength
            return if (len >= 2) TextRange(1, len - 1) else TextRange(0, len)
        }
        return TextRange(0, element.textLength)
    }

    override fun handleContentChange(
        element: BmlInjectionHost,
        range: TextRange,
        newContent: String,
    ): BmlInjectionHost {
        val old = element.text
        val updated = old.substring(0, range.startOffset) + newContent + old.substring(range.endOffset)
        val leaf = element.node.firstChildNode
        if (leaf is LeafElement) {
            leaf.replaceWithText(updated)
        }
        return element
    }
}
