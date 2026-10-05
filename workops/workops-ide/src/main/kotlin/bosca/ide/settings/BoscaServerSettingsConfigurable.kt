package bosca.ide.settings

import bosca.ide.auth.BoscaCliAuthentication
import bosca.ide.server.BoscaConnectionManager
import bosca.ide.server.BoscaServerProfile
import bosca.ide.server.BoscaServerRegistry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

class BoscaServerSettingsConfigurable : Configurable {
    private val registry = BoscaServerRegistry.getInstance()
    private val authentication = BoscaCliAuthentication.getInstance()
    private val model = DefaultListModel<BoscaServerProfile>()
    private val list = JBList(model)
    private val statusLabel = JBLabel(" ")
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Bosca Servers"

    override fun createComponent(): JComponent {
        list.cellRenderer = object : ColoredListCellRenderer<BoscaServerProfile>() {
            override fun customizeCellRenderer(
                list: JList<out BoscaServerProfile>,
                value: BoscaServerProfile,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                append(value.name)
                append("  ${value.graphqlEndpoint}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(
                    "  CLI: ${value.cliProfileName.ifBlank { "matched by endpoint" }}",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                )
            }
        }

        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction { addProfile() }
            .setEditAction { editProfile() }
            .setRemoveAction { removeProfile() }
            .createPanel()
        val testButton = JButton("Test Connection").apply {
            addActionListener { testSelectedProfile() }
        }
        panel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8)
            add(decorated, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(statusLabel, BorderLayout.CENTER)
                add(testButton, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
        reset()
        return panel as JPanel
    }

    override fun isModified(): Boolean =
        model.elements().toList() != registry.profiles()

    @Throws(ConfigurationException::class)
    override fun apply() {
        try {
            registry.replaceProfiles(model.elements().toList())
            statusLabel.text = "Server profiles saved"
        } catch (error: IllegalArgumentException) {
            throw ConfigurationException(error.message ?: "Invalid Bosca server settings")
        }
    }

    override fun reset() {
        model.clear()
        registry.profiles().forEach(model::addElement)
        statusLabel.text = " "
    }

    override fun disposeUIResources() {
        panel = null
        model.clear()
    }

    private fun addProfile() {
        val dialog = BoscaServerProfileDialog(null, loadCliProfiles() ?: return)
        if (!dialog.showAndGet()) return
        val profile = dialog.profile()
        model.addElement(profile)
        list.selectedIndex = model.size() - 1
    }

    private fun editProfile() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        val dialog = BoscaServerProfileDialog(model.get(index), loadCliProfiles() ?: return)
        if (!dialog.showAndGet()) return
        val profile = dialog.profile()
        model.set(index, profile)
    }

    private fun removeProfile() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        val profile = model.get(index)
        if (Messages.showYesNoDialog(
                "Remove '${profile.name}'? Project mappings to this server will no longer resolve. " +
                    "The Bosca CLI profile and its credentials will not be changed.",
                "Remove Bosca Server",
                Messages.getWarningIcon(),
            ) != Messages.YES
        ) return
        model.remove(index)
    }

    private fun testSelectedProfile() {
        val profile = list.selectedValue ?: return
        statusLabel.text = "Connecting to ${profile.name}…"
        BoscaConnectionManager.test(profile).whenComplete { _, error ->
            ApplicationManager.getApplication().invokeLater {
                statusLabel.text = if (error == null) {
                    "Connected to ${profile.name}"
                } else {
                    "Connection failed: ${rootMessage(error)}"
                }
            }
        }
    }

    private fun loadCliProfiles() = try {
        authentication.cliProfiles()
    } catch (error: Exception) {
        Messages.showErrorDialog(
            rootMessage(error),
            "Unable to Read Bosca CLI Profiles",
        )
        null
    }

    private fun rootMessage(error: Throwable): String {
        var current = error
        while (current.cause != null) current = current.cause ?: break
        return current.message ?: current.javaClass.simpleName
    }
}
