package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
class SystemSearchModel(
    val admin: Boolean = false, var configuration: String = "{}", var exists: Boolean = false,
    var previewId: String = "", var preview: JsonElement? = null, var previewed: Boolean = false,
    val feedback: SystemFeedback = SystemFeedback(),
    var reindexMode: String = "", var reindexRequest: Long = 0,
) {
    suspend fun reload(ctx: RenderContext) {
        val current = ctx.gql.execute(SystemSearchConfiguration, Unit).configurations.configuration
        exists = current != null
        configuration = current?.value?.toString() ?: "{}"
    }
    suspend fun save(value: String, ctx: RenderContext) = feedback.perform {
        configuration = value
        val parsed = Json.parseToJsonElement(value)
        val current = ctx.gql.execute(SystemSearchConfiguration, Unit).configurations.configuration
        ctx.gql.execute(SystemSetConfiguration, SystemSetConfiguration.Variables(ConfigurationInput(description = current?.description ?: "Search Transformation Configuration", key = "search", value = parsed, public = current?.public ?: false)))
        exists = true
        feedback.message = "Search configuration saved."
    }
    fun cancelReindex() { reindexMode = ""; feedback.failed = false; feedback.message = "" }
    fun prepareReindex(deleteFirst: Boolean) { reindexMode = if (deleteFirst) "full" else "incremental"; reindexRequest++ }
    suspend fun reindex(deleteFirst: Boolean, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(admin && confirmed) { "Confirm reindexing." }
        check(ctx.gql.execute(SystemReindex, SystemReindex.Variables("Admin Search Index", deleteFirst, true, true, true)).storageSystems.reindex == true) { "Reindexing was not started." }
        reindexMode = ""
        feedback.message = if (deleteFirst) "Full reindex started." else "Reindex started."
    }
    suspend fun preview(id: String, ctx: RenderContext) = feedback.perform {
        previewId = id; preview = null; previewed = false
        require(id.isNotBlank()) { "Enter a metadata ID." }
        java.util.UUID.fromString(id.trim())
        preview = ctx.gql.execute(SystemPreviewMetadata, SystemPreviewMetadata.Variables(id.trim())).search.configuration?.previewMetadata
        previewed = true
    }
}

suspend fun systemSearch(): SystemSearchModel {
    val ctx = currentRenderContext()
    return SystemSearchModel(admin = systemAdministrator(ctx)).also { model -> model.feedback.perform { model.reload(ctx) } }
}
