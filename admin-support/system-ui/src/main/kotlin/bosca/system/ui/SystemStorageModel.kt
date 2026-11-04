package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
class SystemStorageModel(
    val admin: Boolean = false,
    var systems: List<SystemStorageSystemsData.StorageSystems.All> = emptyList(),
    var models: List<SystemStorageModelsData.Ai.Models.All> = emptyList(),
    var draft: SystemStorageDraft = SystemStorageDraft(), var editingId: String = "",
    var editing: Boolean = false, var editorRequest: Long = 0,
    val feedback: SystemFeedback = SystemFeedback(),
    var reindexTarget: String? = null, var reindexRequest: Long = 0,
    var reindexDraft: SystemReindexDraft = SystemReindexDraft(),
) {
    val modelOptions: Map<String, String> get() = models.associate { it.id to "${it.key} — ${it.name}" }
    suspend fun reload(ctx: RenderContext) {
        if (!admin) return
        systems = ctx.gql.execute(SystemStorageSystems, Unit).storageSystems.all
        models = ctx.gql.execute(SystemStorageModels, Unit).ai.models.all
    }
    fun newEntry() { draft = SystemStorageDraft(); editingId = ""; editing = true; editorRequest++ }
    fun cancel() { editing = false; feedback.failed = false; feedback.message = "" }
    fun cancelReindex() { reindexTarget = null; feedback.failed = false; feedback.message = "" }
    suspend fun edit(id: String, ctx: RenderContext) = feedback.perform {
        require(admin) { "Administrator access required." }
        val current = ctx.gql.execute(SystemStorageSystem, SystemStorageSystem.Variables(id)).storageSystems.storageSystem
        requireNotNull(current) { "Storage system no longer exists." }
        draft = SystemStorageDraft(current.name, current.description, current.type.name, current.configuration?.toString() ?: "{}", current.models.map { SystemStorageModelDraft(modelId = it.modelId, configuration = it.configuration.toString()) })
        editingId = current.id; editing = true; editorRequest++
    }
    private fun retain(name: String, description: String, type: String, configuration: String, modelValues: String) {
        draft = SystemStorageDraft(name, description, type, configuration, Json.decodeFromString(ListSerializer(SystemStorageModelDraft.serializer()), modelValues))
    }
    suspend fun addModel(name: String, description: String, type: String, configuration: String, modelValues: String) = feedback.perform {
        retain(name, description, type, configuration, modelValues)
        draft = draft.copy(models = draft.models + SystemStorageModelDraft())
    }
    suspend fun removeModel(key: String, name: String, description: String, type: String, configuration: String, modelValues: String) = feedback.perform {
        retain(name, description, type, configuration, modelValues)
        draft = draft.copy(models = draft.models.filterNot { it.key == key })
    }
    suspend fun save(name: String, description: String, type: String, configuration: String, modelValues: String, ctx: RenderContext) = feedback.perform {
        require(admin) { "Administrator access required." }
        retain(name, description, type, configuration, modelValues)
        require(name.isNotBlank()) { "Enter a storage system name." }
        val input = StorageSystemInput(name = name.trim(), description = description, type = StorageSystemType.valueOf(type), configuration = Json.parseToJsonElement(configuration), models = draft.models.map {
            require(it.modelId.isNotBlank()) { "Choose a model for each association." }
            java.util.UUID.fromString(it.modelId)
            StorageSystemModelInput(Json.parseToJsonElement(it.configuration), it.modelId)
        })
        if (editingId.isBlank()) ctx.gql.execute(SystemAddStorage, SystemAddStorage.Variables(input))
        else ctx.gql.execute(SystemEditStorage, SystemEditStorage.Variables(editingId, input))
        editing = false
        reload(ctx)
        feedback.message = "Storage system saved."
    }
    suspend fun delete(id: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(admin && confirmed) { "Confirm storage system deletion." }
        check(ctx.gql.execute(SystemDeleteStorage, SystemDeleteStorage.Variables(id)).storageSystems.delete) { "Storage system was not deleted." }
        reload(ctx)
        feedback.message = "Storage system deleted."
    }
    fun prepareReindex(storageName: String) { reindexTarget = storageName; reindexRequest++; reindexDraft = SystemReindexDraft() }
    suspend fun reindex(storageName: String, deleteFirst: Boolean, metadata: Boolean, collections: Boolean, profiles: Boolean, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        reindexDraft = SystemReindexDraft(deleteFirst, metadata, collections, profiles, confirmed)
        require(admin && confirmed) { "Confirm reindexing." }
        require(metadata || collections || profiles) { "Select at least one content type." }
        check(ctx.gql.execute(SystemReindex, SystemReindex.Variables(storageName.takeIf(String::isNotBlank), deleteFirst, metadata, collections, profiles)).storageSystems.reindex == true) { "Reindexing was not started." }
        reindexTarget = null
        feedback.message = if (storageName.isBlank()) "Full reindex started." else "Reindex of $storageName started."
    }
}

suspend fun systemStorage(): SystemStorageModel {
    val ctx = currentRenderContext()
    return SystemStorageModel(admin = systemAdministrator(ctx)).also { model -> model.feedback.perform { model.reload(ctx) } }
}
