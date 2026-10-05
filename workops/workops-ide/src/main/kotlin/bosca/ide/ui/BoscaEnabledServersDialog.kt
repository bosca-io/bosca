package bosca.ide.ui

import bosca.ide.project.BoscaProjectSettings
import bosca.ide.server.BoscaServerProfile
import bosca.ide.server.BoscaServerRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal class BoscaEnabledServersDialog(private val project: Project) : DialogWrapper(project) {
    private val profiles: List<BoscaServerProfile> = BoscaServerRegistry.getInstance().profiles()
    private val settings = project.getService(BoscaProjectSettings::class.java)
    private val selectedIds = settings.effectiveProfileIds(profiles.map { it.id })
    private val checkBoxes = profiles.associateWith { profile ->
        JBCheckBox("${profile.name} — ${profile.graphqlEndpoint}", profile.id in selectedIds)
    }

    init {
        title = "Bosca Servers for Project"
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
        if (profiles.isEmpty()) {
            add(JBLabel("No Bosca servers are configured. Add one in Settings | Bosca Servers."))
        } else {
            add(JBLabel("Choose which Bosca servers this project can query:"))
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
            checkBoxes.values.forEach { checkBox ->
                checkBox.alignmentX = Component.LEFT_ALIGNMENT
                add(checkBox)
            }
        }
    }

    override fun doOKAction() {
        settings.setEnabledProfiles(
            checkBoxes.filterValues { it.isSelected }.keys.map { it.id }
        )
        super.doOKAction()
    }
}
