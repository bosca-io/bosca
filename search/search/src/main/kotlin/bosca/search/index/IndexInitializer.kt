package bosca.search.index

import bosca.configuration.model.OpenAIConfiguration
import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.meilisearch.client.MeilisearchApiException
import bosca.meilisearch.client.MeilisearchClient
import bosca.meilisearch.client.model.EmbedderConfig
import bosca.meilisearch.client.waitForTask
import bosca.profile.profile.jobs.ProfileIndexExecutor
import bosca.profile.profile.jobs.ProfileIndexJob
import bosca.search.configuration.ExperimentalConfiguration
import bosca.search.configuration.MeilisearchConfiguration
import bosca.search.model.IndexConfiguration
import bosca.search.pipeline.SearchDocumentPipeline
import bosca.serialization.JsonConverter.toJsonElement
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.enqueue
import bosca.storage.model.StorageSystemType
import bosca.storage.model.StorageSystem
import bosca.storage.service.StorageSystemService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

class IndexInitializer(
    private val configuration: ConfigurationService,
    private val storageSystemService: StorageSystemService,
    private val meilisearchConfiguration: MeilisearchConfiguration,
    private val json: Json,
    private val meilisearchClient: MeilisearchClient,
    private val profileJobQueue: JobQueue,
) {

    suspend fun execute(systemId: UUID) {
        val system = storageSystemService.get(systemId)
        if (system?.type != StorageSystemType.SEARCH) return
        meilisearchConfiguration.experimental?.let {
            val element = json.encodeToJsonElement(ExperimentalConfiguration.serializer(), it)
            val features = (element as? JsonObject)?.mapValues { (_, v) -> v.jsonPrimitive.boolean }
                ?: emptyMap()
            meilisearchClient.updateExperimentalFeatures(features)
        }
        val openAi = try {
            configuration.getValueAs<OpenAIConfiguration>(OpenAIConfiguration.KEY, json)
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            null
        }
        val cfg = json.decodeFromJsonElement<IndexConfiguration>(system.configuration)

        val indexUid = meilisearchConfiguration.indexUid(cfg.name)
        try {
            meilisearchClient.getIndex(indexUid)
        } catch (e: MeilisearchApiException) {
            if (e.code == "index_not_found") {
                val task = meilisearchClient.createIndex(indexUid, cfg.primaryKey)
                meilisearchClient.waitForTask(task.taskUid)
            } else {
                throw e
            }
        }

        val embedders = mutableMapOf<String, EmbedderConfig>()
        for (embedder in cfg.embedders) {
            var key = embedder.apiKey
            if (key == null && openAi?.key != null && embedder.source == "openAi") {
                key = openAi.key
            }
            embedders[embedder.name] = EmbedderConfig(
                source = embedder.source,
                apiKey = key,
                model = embedder.model,
                dimensions = embedder.dimensions,
                documentTemplate = embedder.documentTemplate,
                documentTemplateMaxBytes = embedder.documentTemplateMaxBytes,
            )
        }

        var task = meilisearchClient.updateSortableAttributes(indexUid, cfg.sortable)
        meilisearchClient.waitForTask(task.taskUid)
        task = meilisearchClient.updateSearchableAttributes(indexUid, cfg.searchable)
        meilisearchClient.waitForTask(task.taskUid)
        task = meilisearchClient.updateFilterableAttributes(indexUid, cfg.filterable)
        meilisearchClient.waitForTask(task.taskUid)
        task = meilisearchClient.updateEmbedders(indexUid, embedders)
        meilisearchClient.waitForTask(task.taskUid)

        for (chat in cfg.chatSettings) {
            val prompts = mutableMapOf<String, String>()
            var key = chat.apiKey
            if (key == null && openAi?.key != null && chat.source == "openAi") {
                key = openAi.key
            }
            val settings = mapOf(
                "source" to chat.source,
                "apiKey" to key,
                "prompts" to prompts
            )
            chat.prompts?.let {
                prompts["system"] = it.system
            }
            meilisearchClient.updateChatSettings(meilisearchConfiguration.chatWorkspaceUid(chat.name), settings.toJsonElement().toString())
        }

        system.profileIndexMaintenanceJob()?.enqueue(profileJobQueue, ProfileIndexExecutor::class)
    }
}

internal fun StorageSystem.profileIndexMaintenanceJob(): ProfileIndexJob? = when (name) {
    SearchDocumentPipeline.DEFAULT_INDEX -> ProfileIndexJob(
        storage = bosca.search.IndexStorageSystem(id, name),
        deleteFirst = true,
        deleteOnly = true,
    )
    SearchDocumentPipeline.PROFILE_INDEX -> ProfileIndexJob(
        storage = bosca.search.IndexStorageSystem(id, name),
        deleteFirst = true,
    )
    else -> null
}
