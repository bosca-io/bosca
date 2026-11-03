package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
class SystemConfigurationsModel(
    val admin: Boolean = false,
    var entries: List<SystemConfigurationsData.Configurations.All> = emptyList(),
    var search: String = "", var editing: Boolean = false, var editingKey: String? = null,
    var draft: SystemConfigurationDraft = SystemConfigurationDraft(),
    val feedback: SystemFeedback = SystemFeedback(),
) {
    val filtered: List<SystemConfigurationsData.Configurations.All> get() = entries.filter {
        it.key.contains(search, ignoreCase = true) || it.description.contains(search, ignoreCase = true)
    }
    suspend fun reload(ctx: RenderContext) {
        entries = ctx.gql.execute(SystemConfigurations, Unit).configurations.all
    }
    fun filter(value: String) { search = value }
    fun newEntry() { editing = true; editingKey = null; draft = SystemConfigurationDraft() }
    fun cancel() { editing = false; editingKey = null; draft = SystemConfigurationDraft() }
    suspend fun edit(key: String, ctx: RenderContext) = feedback.perform {
        val entry = ctx.gql.execute(SystemConfiguration, SystemConfiguration.Variables(key)).configurations.configuration
        requireNotNull(entry) { "Configuration no longer exists." }
        editing = true
        editingKey = entry.key
        draft = SystemConfigurationDraft(entry.key, entry.description, (entry.value ?: kotlinx.serialization.json.JsonNull).toString(), entry.public)
    }
    suspend fun save(key: String, description: String, value: String, public: Boolean, ctx: RenderContext) = feedback.perform {
        draft = SystemConfigurationDraft(key, description, value, public)
        editing = true
        require(key.isNotBlank()) { "Enter a configuration key." }
        require(editingKey == null || editingKey == key) { "The key of an existing configuration cannot be changed." }
        val parsed = Json.parseToJsonElement(value)
        // Check key existence under the caller; the service retains the ACL when permissions are omitted.
        val current = ctx.gql.execute(SystemConfiguration, SystemConfiguration.Variables(key)).configurations.configuration
        require(editingKey != null || current == null) { "This key already exists. Open it for editing." }
        ctx.gql.execute(SystemSetConfiguration, SystemSetConfiguration.Variables(ConfigurationInput(description = description, key = key, public = public, value = parsed)))
        cancel()
        reload(ctx)
        feedback.message = "Configuration saved."
    }
    suspend fun delete(key: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm configuration deletion." }
        ctx.gql.execute(SystemDeleteConfiguration, SystemDeleteConfiguration.Variables(key))
        if (editingKey == key) cancel()
        reload(ctx)
        feedback.message = "Configuration deleted."
    }
}

suspend fun systemConfigurations(): SystemConfigurationsModel {
    val ctx = currentRenderContext()
    return SystemConfigurationsModel(admin = systemAdministrator(ctx)).also { model -> model.feedback.perform { model.reload(ctx) } }
}
