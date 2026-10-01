package bosca.ide.settings

import bosca.ide.auth.BoscaCliProfile
import bosca.ide.auth.sameEndpoint
import bosca.ide.server.BoscaServerProfile
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JList

internal class BoscaServerProfileDialog(
    private val original: BoscaServerProfile?,
    private val cliProfiles: List<BoscaCliProfile>,
) : DialogWrapper(true) {
    private val nameField = JBTextField(original?.name.orEmpty(), 32)
    private val cliProfileField = ComboBox(DefaultComboBoxModel(cliProfiles.toTypedArray()))
    private val graphqlField = JBTextField(original?.graphqlEndpoint.orEmpty(), 42).apply { isEditable = false }
    private val webSocketField = JBTextField(original?.webSocketEndpoint.orEmpty(), 42)
    private val authLabel = JBLabel()

    init {
        title = if (original == null) "Add Bosca Server" else "Edit Bosca Server"
        cliProfileField.renderer = object : SimpleListCellRenderer<BoscaCliProfile>() {
            override fun customize(
                list: JList<out BoscaCliProfile>,
                value: BoscaCliProfile,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                text = buildString {
                    append(value.name)
                    append(" — ")
                    append(value.endpoint)
                    append(if (value.authenticated) " (authenticated)" else " (login required)")
                }
            }
        }
        val initial = original?.cliProfileName
            ?.takeIf { it.isNotBlank() }
            ?.let { name -> cliProfiles.firstOrNull { it.name == name } }
            ?: original?.let { saved ->
                val matches = cliProfiles.filter { sameEndpoint(it.endpoint, saved.graphqlEndpoint) }
                matches.firstOrNull { it.active } ?: matches.singleOrNull()
            }
            ?: cliProfiles.firstOrNull { it.active }
            ?: cliProfiles.firstOrNull()
        cliProfileField.selectedItem = initial
        if (original == null) applyCliProfile(initial)
        updateAuthLabel(initial)
        cliProfileField.addActionListener {
            selectedCliProfile()?.let(::applyCliProfile)
            updateAuthLabel(selectedCliProfile())
        }
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Bosca CLI profile:", cliProfileField)
        .addLabeledComponent("Name:", nameField)
        .addLabeledComponent("GraphQL endpoint:", graphqlField)
        .addLabeledComponent("WebSocket endpoint:", webSocketField)
        .addComponentToRightColumn(authLabel)
        .panel

    override fun doValidate(): ValidationInfo? {
        if (selectedCliProfile() == null) {
            return ValidationInfo(
                "No Bosca CLI profiles are available. Run 'bosca login --profile <name>' first.",
                cliProfileField,
            )
        }
        return try {
            profile().normalized()
            null
        } catch (error: IllegalArgumentException) {
            ValidationInfo(error.message ?: "Invalid Bosca server profile", graphqlField)
        }
    }

    fun profile(): BoscaServerProfile {
        val cliProfile = selectedCliProfile()
            ?: throw IllegalArgumentException("A Bosca CLI profile is required")
        return BoscaServerProfile(
            id = original?.id.orEmpty(),
            name = nameField.text,
            graphqlEndpoint = cliProfile.endpoint,
            webSocketEndpoint = webSocketField.text,
            cliProfileName = cliProfile.name,
        ).normalized()
    }

    private fun selectedCliProfile(): BoscaCliProfile? = cliProfileField.selectedItem as? BoscaCliProfile

    private fun applyCliProfile(profile: BoscaCliProfile?) {
        if (profile == null) return
        nameField.text = profile.name
        graphqlField.text = profile.endpoint
        webSocketField.text = ""
    }

    private fun updateAuthLabel(profile: BoscaCliProfile?) {
        authLabel.text = when {
            profile == null -> "Create a profile with the Bosca CLI before configuring the plugin."
            profile.authenticated -> "Credentials and session refresh are managed by the Bosca CLI."
            else -> "Run 'bosca login --profile ${profile.name}' before connecting."
        }
    }
}
