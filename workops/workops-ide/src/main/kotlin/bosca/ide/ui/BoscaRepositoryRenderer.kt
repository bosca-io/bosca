package bosca.ide.ui

import bosca.ide.repository.BoscaRemoteRepository
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList

internal class BoscaRepositoryRenderer : ColoredListCellRenderer<BoscaRemoteRepository>() {
    override fun customizeCellRenderer(
        list: JList<out BoscaRemoteRepository>,
        value: BoscaRemoteRepository,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        append(value.name)
        append("  ${value.slug}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}
