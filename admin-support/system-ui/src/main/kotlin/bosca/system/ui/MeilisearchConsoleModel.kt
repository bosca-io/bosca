package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.ViewTab
import bosca.system.ui.graphql.*
import bosca.system.ui.graphql.SystemGetMeilisearchAdminOverviewData.MeilisearchAdmin as MeiliOverview
import bosca.system.ui.graphql.SystemGetMeilisearchIndexSettingsData.MeilisearchAdmin.Index.Settings as MeiliSettings
import bosca.system.ui.graphql.SystemGetMeilisearchEmbeddersData.MeilisearchAdmin.Index.Settings.Embedders as MeiliEmbedder
import bosca.system.ui.graphql.SystemGetStorageSystemsForMeilisearchData.StorageSystems.All as MeiliStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class MeilisearchConsoleModel(
    val admin: Boolean,
    var tab: String = "nodes", var overview: MeiliOverview? = null, var storageSystems: List<MeiliStorage> = emptyList(),
    var indexUid: String = "", var indexTab: String = "stats", var indexRequest: Long = 0,
    var settings: MeiliSettings? = null, var embedders: List<MeiliEmbedder> = emptyList(),
    var documents: List<JsonElement> = emptyList(), var documentsTotal: Long = 0, var documentsOffset: Int = 0,
    var editor: String = "", var editorId: String = "", var editorRequest: Long = 0, var editorCompleted: Boolean = false,
    var nodeDraft: MeilisearchNodeDraft = MeilisearchNodeDraft(), var embedderDraft: MeilisearchEmbedderDraft = MeilisearchEmbedderDraft(),
    var newIndexUid: String = "", var newPrimaryKey: String = "", var assignStorageId: String = "",
    var operationKind: String = "", var operationResource: String = "", var operationTarget: String = "",
    var operationRequest: Long = 0, var operationCompleted: Boolean = false, var operationNotice: String = "", var noticeSequence: Int = 0,
    val feedback: SystemFeedback = SystemFeedback(), val storageFeedback: SystemFeedback = SystemFeedback(),
    val settingsFeedback: SystemFeedback = SystemFeedback(), val embedderFeedback: SystemFeedback = SystemFeedback(),
    val documentsFeedback: SystemFeedback = SystemFeedback(), val editorFeedback: SystemFeedback = SystemFeedback(),
) {
    val tabs get() = listOf(ViewTab("nodes", "Nodes"), ViewTab("indexes", "Indexes"), ViewTab("tasks", "Tasks"), ViewTab("keys", "Keys"))
    val indexTabs get() = listOf(ViewTab("stats", "Stats"), ViewTab("settings", "Settings"), ViewTab("embedders", "Embedders"), ViewTab("documents", "Documents"))
    val selectedIndex get() = overview?.indexes?.find { it.uid == indexUid }
    val selectedNode get() = overview?.nodes?.find { it.id == editorId }
    val availableStorage get() = storageSystems.filter { it.id !in selectedNode?.storageSystemIds.orEmpty() }
    val indexOpenRequest get() = if (indexUid.isBlank()) "" else "index-$indexRequest"
    val editorOpenRequest get() = if (editor.isBlank()) "" else "editor-$editorRequest"
    val operationOpenRequest get() = if (operationKind.isBlank()) "" else "operation-$operationRequest"
    val documentsEnd get() = (documentsOffset.toLong() + documents.size).coerceAtMost(documentsTotal)
    val settingsGroups get() = settings?.let { listOf("Ranking Rules" to it.rankingRules, "Searchable Attributes" to it.searchableAttributes,
        "Filterable Attributes" to it.filterableAttributes, "Sortable Attributes" to it.sortableAttributes,
        "Displayed Attributes" to it.displayedAttributes, "Stop Words" to it.stopWords) }.orEmpty()
    val synonymsJson get() = settings?.let { Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(MeiliSettings.Synonyms.serializer()), it.synonyms) } ?: JsonArray(emptyList())
    val subtitle get() = overview?.let { "v${it.version.pkgVersion} · ${if (it.health.healthy) "healthy" else "unhealthy"}" }.orEmpty()
    val operationTitle get() = when (operationKind) {
        "deleteNode" -> "Delete node?"; "deleteIndex" -> "Delete index?"; "clearDocuments" -> "Delete all documents?"
        "removeStorage" -> "Remove storage assignment?"; "removeEmbedder" -> "Remove embedder?"; "cancelTask" -> "Cancel task?"
        else -> "Confirm Meilisearch operation"
    }
    val operationDescription get() = when (operationKind) {
        "deleteNode" -> "Delete node ${overview?.nodes?.find { it.id == operationResource }?.name ?: operationResource}?"
        "deleteIndex" -> "Delete index \"$operationResource\"? This cannot be undone."
        "clearDocuments" -> "Delete all documents from \"$operationResource\"? This cannot be undone."
        "removeStorage" -> "Remove ${storageName(operationTarget)} from ${overview?.nodes?.find { it.id == operationResource }?.name ?: operationResource}?"
        "removeEmbedder" -> "Remove embedder \"$operationTarget\" from $operationResource?"
        "cancelTask" -> "Cancel task #$operationResource?"
        else -> ""
    }
    fun storageName(id: String) = storageSystems.find { it.id == id }?.name ?: id
    private fun authorize() = require(admin) { "Administrator access is required." }
    private suspend fun overviewData(ctx: RenderContext) { overview = ctx.gql.execute(SystemGetMeilisearchAdminOverview, Unit).meilisearchAdmin }
    private suspend fun settingsData(ctx: RenderContext) {
        settings = requireNotNull(ctx.gql.execute(SystemGetMeilisearchIndexSettings, SystemGetMeilisearchIndexSettings.Variables(indexUid)).meilisearchAdmin.index) { "Index not found: $indexUid" }.settings
    }
    private suspend fun embedderData(ctx: RenderContext) {
        embedders = requireNotNull(ctx.gql.execute(SystemGetMeilisearchEmbedders, SystemGetMeilisearchEmbedders.Variables(indexUid)).meilisearchAdmin.index) { "Index not found: $indexUid" }.settings.embedders
    }
    private suspend fun documentData(offset: Int, ctx: RenderContext) {
        require(offset >= 0) { "Document offset must not be negative." }
        val result = requireNotNull(ctx.gql.execute(SystemGetMeilisearchIndexDocuments, SystemGetMeilisearchIndexDocuments.Variables(indexUid, offset, 20)).meilisearchAdmin.index) { "Index not found: $indexUid" }
        if (offset > 0 && offset >= result.stats.numberOfDocuments) {
            return documentData(((result.stats.numberOfDocuments - 1).coerceAtLeast(0) / 20 * 20).toInt(), ctx)
        }
        documents = result.documents; documentsTotal = result.stats.numberOfDocuments; documentsOffset = offset
    }
    suspend fun refresh(ctx: RenderContext) {
        feedback.perform { authorize(); overviewData(ctx) }
        storageFeedback.perform { authorize(); storageSystems = ctx.gql.execute(SystemGetStorageSystemsForMeilisearch, Unit).storageSystems.all }
    }
    fun chooseTab(value: String) { authorize(); require(tabs.any { it.value == value }); tab = value }
    fun chooseIndexTab(value: String) { authorize(); require(indexTabs.any { it.value == value }); indexTab = value; indexRequest++ }
    suspend fun openIndex(uid: String, ctx: RenderContext) {
        feedback.perform { authorize(); require(overview?.indexes?.any { it.uid == uid } == true) { "Index not found: $uid" }
            indexUid = uid; indexTab = "stats"; indexRequest++; settings = null; embedders = emptyList(); documents = emptyList(); documentsTotal = 0; documentsOffset = 0
        }
        if (!feedback.failed) refreshIndex(ctx)
    }
    suspend fun refreshIndex(ctx: RenderContext) {
        indexRequest++
        settingsFeedback.perform { authorize(); settingsData(ctx) }
        embedderFeedback.perform { authorize(); embedderData(ctx) }
        documentsFeedback.perform { authorize(); documentData(documentsOffset, ctx) }
    }
    suspend fun loadDocuments(offset: Int, ctx: RenderContext) = documentsFeedback.perform { authorize(); indexRequest++; documentData(offset, ctx) }
    fun cancelEditor() {
        editor = ""; editorId = ""; editorCompleted = true
        nodeDraft = MeilisearchNodeDraft(); embedderDraft = MeilisearchEmbedderDraft()
        editorFeedback.failed = false; editorFeedback.message = ""
    }
    fun cancelOperation() {
        operationKind = ""; operationResource = ""; operationTarget = ""; operationCompleted = false
        feedback.failed = false; feedback.message = ""
    }
    fun openEditor(kind: String, id: String) {
        authorize(); require(kind in setOf("node", "index", "assign", "embedder"))
        editor = kind; editorId = id; editorRequest++; editorCompleted = false; editorFeedback.message = ""; editorFeedback.failed = false
        when (kind) {
            "node" -> nodeDraft = if (id.isBlank()) MeilisearchNodeDraft() else requireNotNull(overview?.nodes?.find { it.id == id }).let { MeilisearchNodeDraft(it.name, it.description, it.url, types = it.types.map { type -> type.name }) }
            "index" -> { newIndexUid = ""; newPrimaryKey = "" }
            "assign" -> { requireNotNull(selectedNode); assignStorageId = "" }
            "embedder" -> embedderDraft = if (id.isBlank()) MeilisearchEmbedderDraft() else requireNotNull(embedders.find { it.name == id }).let {
                MeilisearchEmbedderDraft(it.name, it.source.orEmpty(), it.model.orEmpty(), dimensions = it.dimensions?.toString().orEmpty(), documentTemplate = it.documentTemplate.orEmpty(),
                    documentTemplateMaxBytes = it.documentTemplateMaxBytes?.toString().orEmpty(), url = it.url.orEmpty(), request = it.request?.toString() ?: "null", response = it.response?.toString() ?: "null", headers = it.headers?.toString() ?: "null")
            }
        }
    }
    suspend fun createIndex(uid: String, primaryKey: String, ctx: RenderContext) {
        editorCompleted = false; editorRequest++
        newIndexUid = uid; newPrimaryKey = primaryKey
        editorFeedback.perform { authorize(); require(uid.isNotBlank()) { "UID is required." }
            val task = ctx.gql.execute(SystemCreateMeilisearchIndex, SystemCreateMeilisearchIndex.Variables(uid.trim(), primaryKey.takeIf(String::isNotBlank))).meilisearchAdmin.createIndex
            editorCompleted = true; operationNotice = "Index ${uid.trim()} creation queued (task #${task.uid} · ${task.status})."; noticeSequence++
        }
        if (editorCompleted) refresh(ctx)
    }
    suspend fun saveNode(name: String, description: String, url: String, key: String, search: Boolean, vector: Boolean, supplementary: Boolean, ctx: RenderContext) {
        editorCompleted = false; editorRequest++
        val types = listOfNotNull("SEARCH".takeIf { search }, "VECTOR".takeIf { vector }, "SUPPLEMENTARY".takeIf { supplementary })
        nodeDraft = MeilisearchNodeDraft(name, description, url, key, types)
        editorFeedback.perform { authorize(); require(name.isNotBlank() && url.isNotBlank()) { "Name and URL are required." }
            val selectedTypes = types.map(StorageSystemType::valueOf)
            if (editorId.isBlank()) {
                require(key.isNotBlank()) { "API key is required for a new node." }
                ctx.gql.execute(SystemAddMeilisearchNode, SystemAddMeilisearchNode.Variables(MeilisearchNodeInput(name.trim(), description, url.trim(), key, selectedTypes)))
            } else ctx.gql.execute(SystemEditMeilisearchNode, SystemEditMeilisearchNode.Variables(editorId, MeilisearchEditNodeInput(name.trim(), description, url.trim(), key.takeIf(String::isNotBlank), selectedTypes)))
            editorCompleted = true; nodeDraft = nodeDraft.copy(key = ""); operationNotice = "${if (editorId.isBlank()) "Added" else "Updated"} ${name.trim()}."; noticeSequence++
        }
        if (editorCompleted) refresh(ctx)
    }
    suspend fun assignStorage(id: String, ctx: RenderContext) {
        editorCompleted = false; editorRequest++
        assignStorageId = id
        editorFeedback.perform { authorize(); require(availableStorage.any { it.id == id }) { "Select an unassigned storage system." }
            require(ctx.gql.execute(SystemAssignMeilisearchNodeToStorage, SystemAssignMeilisearchNodeToStorage.Variables(editorId, id)).meilisearchAdmin.assignNodeToStorageSystem) { "Storage assignment was not saved." }
            editorCompleted = true; operationNotice = "Storage system assigned."; noticeSequence++
        }
        if (editorCompleted) refresh(ctx)
    }
    suspend fun updateSettings(ctx: RenderContext) {
        feedback.perform { authorize(); val value = requireNotNull(settings) { "Load the index settings first." }
            val input = MeilisearchSettingsInput(value.rankingRules, value.searchableAttributes, value.displayedAttributes, value.filterableAttributes, value.sortableAttributes, value.stopWords)
            val task = ctx.gql.execute(SystemUpdateMeilisearchSettings, SystemUpdateMeilisearchSettings.Variables(indexUid, input)).meilisearchAdmin.updateSettings
            operationNotice = "Settings update queued for $indexUid (task #${task.uid} · ${task.status})."; noticeSequence++
        }
        if (!feedback.failed) refresh(ctx)
    }
    fun retainEmbedder(name: String, source: String, model: String, key: String, dimensions: String, template: String, maxBytes: String, url: String, request: String, response: String, headers: String) {
        embedderDraft = MeilisearchEmbedderDraft(name, source, model, key, dimensions, template, maxBytes, url, request, response, headers)
    }
    fun changeEmbedderSource(name: String, source: String, model: String, key: String, dimensions: String, template: String, maxBytes: String, url: String, request: String, response: String, headers: String) {
        authorize(); editorRequest++
        val previous = embedderDraft
        retainEmbedder(name, source, model, key, dimensions, template, maxBytes, url,
            if (previous.source == "rest") request else previous.request,
            if (previous.source == "rest") response else previous.response,
            if (previous.source == "rest") headers else previous.headers)
    }
    suspend fun saveEmbedder(name: String, source: String, model: String, key: String, dimensions: String, template: String, maxBytes: String, url: String, request: String, response: String, headers: String, ctx: RenderContext) {
        editorCompleted = false; editorRequest++
        retainEmbedder(name, source, model, key, dimensions, template, maxBytes, url, request, response, headers)
        editorFeedback.perform { authorize()
            val value = embedderDraft.input()
            require(editorId.isBlank() || value.name == editorId) { "An existing embedder's name cannot be changed." }
            require(editorId.isNotBlank() || embedders.none { it.name == value.name }) { "An embedder with this name already exists." }
            val task = ctx.gql.execute(SystemUpdateMeilisearchEmbedders, SystemUpdateMeilisearchEmbedders.Variables(indexUid, listOf(value))).meilisearchAdmin.updateEmbedders
            editorCompleted = true; embedderDraft = embedderDraft.copy(key = ""); operationNotice = "Embedder ${value.name} update queued (task #${task.uid} · ${task.status})."; noticeSequence++
        }
        if (editorCompleted) { embedderFeedback.perform { embedderData(ctx) }; feedback.perform { overviewData(ctx) } }
    }
    fun prepareOperation(kind: String, resource: String, target: String) {
        authorize(); require(kind in setOf("deleteNode", "deleteIndex", "clearDocuments", "removeStorage", "removeEmbedder", "cancelTask"))
        operationKind = kind; operationResource = resource; operationTarget = target; operationRequest++; operationCompleted = false
        feedback.message = ""; feedback.failed = false
    }
    suspend fun confirmOperation(confirmed: Boolean, ctx: RenderContext) {
        operationCompleted = false; operationRequest++
        feedback.perform {
            authorize(); require(confirmed) { "Confirm this operation." }
            val taskNotice = when (operationKind) {
                "deleteNode" -> { require(ctx.gql.execute(SystemDeleteMeilisearchNode, SystemDeleteMeilisearchNode.Variables(operationResource)).meilisearchAdmin.deleteNode) { "Node was not deleted." }; "Node deleted." }
                "removeStorage" -> { require(ctx.gql.execute(SystemRemoveMeilisearchNodeFromStorage, SystemRemoveMeilisearchNodeFromStorage.Variables(operationResource, operationTarget)).meilisearchAdmin.removeNodeFromStorageSystem) { "Storage assignment was not removed." }; "Storage assignment removed." }
                "deleteIndex" -> ctx.gql.execute(SystemDeleteMeilisearchIndex, SystemDeleteMeilisearchIndex.Variables(operationResource)).meilisearchAdmin.deleteIndex.let { "Index deletion queued (task #${it.uid} · ${it.status})." }
                "clearDocuments" -> ctx.gql.execute(SystemDeleteAllMeilisearchDocuments, SystemDeleteAllMeilisearchDocuments.Variables(operationResource)).meilisearchAdmin.deleteAllDocuments.let { "Document deletion queued (task #${it.uid} · ${it.status})." }
                "cancelTask" -> {
                    val uid = requireNotNull(operationResource.toIntOrNull()?.takeIf { it >= 0 }) { "Invalid task UID." }
                    ctx.gql.execute(SystemCancelMeilisearchTasks, SystemCancelMeilisearchTasks.Variables(listOf(uid))).meilisearchAdmin.cancelTasks.let { "Cancellation queued for task #$uid (task #${it.uid} · ${it.status})." }
                }
                "removeEmbedder" -> {
                    if (embedders.singleOrNull()?.name == operationTarget) ctx.gql.execute(SystemResetMeilisearchEmbedders, SystemResetMeilisearchEmbedders.Variables(operationResource)).meilisearchAdmin.resetEmbedders.let { "Embedder removal queued (task #${it.uid} · ${it.status})." }
                    else ctx.gql.execute(SystemRemoveMeilisearchEmbedder, SystemRemoveMeilisearchEmbedder.Variables(operationResource, operationTarget)).meilisearchAdmin.removeEmbedder.let { "Embedder removal queued (task #${it.uid} · ${it.status})." }
                }
                else -> error("Select a Meilisearch operation.")
            }
            operationCompleted = true; operationNotice = taskNotice; noticeSequence++
            if (operationKind == "deleteIndex" && indexUid == operationResource) indexUid = ""
        }
        if (operationCompleted) refresh(ctx)
    }
}

suspend fun meilisearchConsole(): MeilisearchConsoleModel {
    val ctx = currentRenderContext()
    val admin = ctx.gql.execute(SystemAccess, Unit).security.principals.current.groups.any { it.name in setOf("sa", "administrators") }
    return MeilisearchConsoleModel(admin).also { if (admin) it.refresh(ctx) }
}
