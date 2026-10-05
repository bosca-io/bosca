@file:OptIn(ExperimentalUuidApi::class)

package bosca.meilisearch.admin.service

import bosca.db.transaction
import bosca.meilisearch.admin.model.MeilisearchEmbedderEntry
import bosca.meilisearch.admin.model.MeilisearchFaceting
import bosca.meilisearch.admin.model.MeilisearchFieldDistribution
import bosca.meilisearch.admin.model.MeilisearchGlobalStats
import bosca.meilisearch.admin.model.MeilisearchIndexInfo
import bosca.meilisearch.admin.model.MeilisearchIndexSettings
import bosca.meilisearch.admin.model.MeilisearchIndexStats
import bosca.meilisearch.admin.model.MeilisearchKey
import bosca.meilisearch.admin.model.MeilisearchMinWordSize
import bosca.meilisearch.admin.model.MeilisearchNetworkRemote
import bosca.meilisearch.admin.model.MeilisearchNode
import bosca.meilisearch.admin.model.MeilisearchPagination
import bosca.meilisearch.admin.model.MeilisearchSynonym
import bosca.meilisearch.admin.model.MeilisearchTask
import bosca.meilisearch.admin.model.MeilisearchTaskError
import bosca.meilisearch.admin.model.MeilisearchTypoTolerance
import bosca.meilisearch.admin.model.MeilisearchVersion
import bosca.meilisearch.admin.repository.MeilisearchNodeRepository
import bosca.meilisearch.client.MeilisearchClient
import bosca.meilisearch.client.model.EmbedderConfig
import bosca.meilisearch.client.model.Settings
import bosca.meilisearch.client.model.Task
import bosca.meilisearch.client.model.TaskInfo
import bosca.security.encryption.EncryptionService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.storage.model.StorageSystemType
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Implements administrative operations against Meilisearch using [MeilisearchClient]
 * for all HTTP communication. Manages multi-node topology via the
 * [MeilisearchNodeRepository] and creates temporary [MeilisearchClient] instances
 * to communicate with individual nodes.
 */
@ServiceImplementation
class MeilisearchAdminServiceImpl(
    private val client: MeilisearchClient,
    private val nodeRepository: MeilisearchNodeRepository,
    private val encryption: EncryptionService,
    private val json: Json
) : MeilisearchAdminService {

    private val logger = LoggerFactory.getLogger(MeilisearchAdminServiceImpl::class.java)

    private val nodeCache = Caffeine.newBuilder()
        .maximumSize(100)
        .expireAfterWrite(Duration.ofSeconds(5))
        .build<UUID, MeilisearchNode>()

    // ── Server Info ──

    override suspend fun getVersion(): MeilisearchVersion {
        val version = client.getVersion()
        return MeilisearchVersion(
            pkgVersion = version.pkgVersion,
            commitDate = version.commitDate,
            commitSha = version.commitSha,
        )
    }

    override suspend fun getHealth(): Boolean {
        return try {
            client.health()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    override suspend fun getStats(): MeilisearchGlobalStats {
        val stats = client.getStats()
        return MeilisearchGlobalStats(
            numberOfIndexes = stats.indexes?.size ?: 0,
            databaseSize = stats.databaseSize,
            lastUpdate = stats.lastUpdate,
        )
    }

    // ── Index Management ──

    override suspend fun getIndexes(offset: Int?, limit: Int?): List<MeilisearchIndexInfo> {
        val results = client.getIndexes(offset, limit)
        return results.results.map { idx ->
            MeilisearchIndexInfo(
                uid = idx.uid,
                primaryKey = idx.primaryKey,
                createdAt = idx.createdAt ?: "",
                updatedAt = idx.updatedAt ?: "",
            )
        }
    }

    override suspend fun getIndex(uid: String): MeilisearchIndexInfo? {
        return try {
            val idx = client.getIndex(uid)
            MeilisearchIndexInfo(
                uid = idx.uid,
                primaryKey = idx.primaryKey,
                createdAt = idx.createdAt ?: "",
                updatedAt = idx.updatedAt ?: "",
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn("Failed to get index '{}'", uid, e)
            null
        }
    }

    override suspend fun getIndexStats(uid: String): MeilisearchIndexStats {
        val stats = client.getIndexStats(uid)
        return MeilisearchIndexStats(
            numberOfDocuments = stats.numberOfDocuments,
            isIndexing = stats.isIndexing,
            fieldDistribution = stats.fieldDistribution?.map { (field, count) ->
                MeilisearchFieldDistribution(field = field, count = count.toLong())
            } ?: emptyList(),
        )
    }

    override suspend fun getIndexSettings(uid: String): MeilisearchIndexSettings {
        val settings = client.getSettings(uid)
        return convertSettings(settings)
    }

    override suspend fun getDocuments(
        uid: String,
        offset: Int?,
        limit: Int?,
        fields: List<String>?,
    ): List<JsonElement> {
        return try {
            val response = client.getDocuments(uid, offset, limit, fields)
            response.results
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn("Failed to parse documents for index '{}'", uid, e)
            emptyList()
        }
    }

    override suspend fun createIndex(uid: String, primaryKey: String?): MeilisearchTask {
        val taskInfo = client.createIndex(uid, primaryKey)
        return convertTaskInfo(taskInfo)
    }

    override suspend fun deleteIndex(uid: String): MeilisearchTask {
        val taskInfo = client.deleteIndex(uid)
        return convertTaskInfo(taskInfo)
    }

    override suspend fun swapIndexes(pairs: List<List<String>>): MeilisearchTask {
        require(pairs.all { it.size == 2 }) { "Each swap pair must contain exactly two index UIDs" }
        val swapPairs = pairs.map { pair -> pair[0] to pair[1] }
        val taskInfo = client.swapIndexes(swapPairs)
        return convertTaskInfo(taskInfo)
    }

    override suspend fun updateSettings(uid: String, settings: MeilisearchSettingsInput): MeilisearchTask {
        val clientSettings = Settings(
            rankingRules = settings.rankingRules,
            searchableAttributes = settings.searchableAttributes,
            displayedAttributes = settings.displayedAttributes,
            filterableAttributes = settings.filterableAttributes,
            sortableAttributes = settings.sortableAttributes,
            stopWords = settings.stopWords,
            distinctAttribute = settings.distinctAttribute,
            proximityPrecision = settings.proximityPrecision,
            searchCutoffMs = settings.searchCutoffMs,
        )
        val taskInfo = client.updateSettings(uid, clientSettings)
        return convertTaskInfo(taskInfo)
    }

    override suspend fun resetSettings(uid: String): MeilisearchTask {
        val taskInfo = client.resetSettings(uid)
        return convertTaskInfo(taskInfo)
    }

    override suspend fun updateEmbedders(uid: String, embedders: List<MeilisearchEmbedderInput>): MeilisearchTask {
        val embeddersMap = mutableMapOf<String, EmbedderConfig>()
        for (input in embedders) {
            embeddersMap[input.name] = EmbedderConfig(
                source = input.source,
                model = input.model,
                apiKey = input.key,
                dimensions = input.dimensions,
                documentTemplate = input.documentTemplate,
                documentTemplateMaxBytes = input.documentTemplateMaxBytes,
                url = input.url,
                request = input.request,
                response = input.response,
                headers = input.headers,
            )
        }
        val taskInfo = client.updateEmbedders(uid, embeddersMap)
        return convertTaskInfo(taskInfo)
    }

    override suspend fun resetEmbedders(uid: String): MeilisearchTask {
        val taskInfo = client.resetEmbedders(uid)
        return convertTaskInfo(taskInfo)
    }

    // ── Documents ──

    override suspend fun deleteAllDocuments(uid: String): MeilisearchTask {
        val taskInfo = client.deleteAllDocuments(uid)
        return convertTaskInfo(taskInfo)
    }

    override suspend fun deleteDocuments(uid: String, ids: List<String>): MeilisearchTask {
        val idx = client.getIndex(uid)
        val primaryKey = idx.primaryKey ?: "id"
        val escapedIds = ids.joinToString(", ") { "\"${it.replace("\"", "\\\"")}\"" }
        val filter = "$primaryKey IN [$escapedIds]"
        val taskInfo = client.deleteDocumentsByFilter(uid, filter)
        return convertTaskInfo(taskInfo)
    }

    override suspend fun deleteDocumentsByFilter(uid: String, filter: String): MeilisearchTask {
        val taskInfo = client.deleteDocumentsByFilter(uid, filter)
        return convertTaskInfo(taskInfo)
    }

    // ── Tasks ──

    override suspend fun getTasks(
        limit: Int?,
        from: Int?,
        status: List<String>?,
        type: List<String>?,
        indexUid: List<String>?,
    ): List<MeilisearchTask> {
        val results = client.getTasks(limit, from, status, type, indexUid)
        return results.results.map { task -> convertTask(task) }
    }

    override suspend fun getTask(uid: Int): MeilisearchTask {
        val task = client.getTask(uid)
        return convertTask(task)
    }

    override suspend fun cancelTasks(
        uids: List<Int>?,
        statuses: List<String>?,
        types: List<String>?,
        indexUids: List<String>?,
    ): MeilisearchTask {
        require(uids != null || statuses != null || types != null || indexUids != null) {
            "At least one filter parameter (uids, statuses, types, or indexUids) is required to cancel tasks"
        }
        val taskInfo = client.cancelTasks(uids, statuses, types, indexUids)
        return convertTaskInfo(taskInfo)
    }

    // ── Keys ──

    override suspend fun getKeys(): List<MeilisearchKey> {
        val results = client.getKeys()
        return results.results.map { key ->
            val maskedKey = key.key?.let { k ->
                if (k.length > 8) "${k.take(4)}..${k.takeLast(4)}" else "****"
            } ?: ""
            MeilisearchKey(
                name = key.name,
                description = key.description ?: "",
                uid = key.uid ?: "",
                key = maskedKey,
                actions = key.actions ?: emptyList(),
                indexes = key.indexes ?: emptyList(),
                expiresAt = key.expiresAt,
                createdAt = key.createdAt ?: "",
                updatedAt = key.updatedAt ?: "",
            )
        }
    }

    // ── Instance Operations ──

    override suspend fun createDump(): MeilisearchTask {
        val taskInfo = client.createDump()
        return convertTaskInfo(taskInfo)
    }

    override suspend fun createSnapshot(): MeilisearchTask {
        val taskInfo = client.createSnapshot()
        return convertTaskInfo(taskInfo)
    }

    // ── Node Management ──

    private suspend fun encryptApiKey(plaintext: String, id: UUID): EncryptionService.Encrypted {
        return encryption.encrypt(plaintext.toByteArray(Charsets.UTF_8), id)
    }

    private suspend fun decryptNode(node: MeilisearchNode): MeilisearchNode {
        if (node.apiKey != null && node.apiKeyNonce != null) {
            val encrypted = EncryptionService.Encrypted(node.apiKeyNonce, node.apiKey)
            val plaintext = encryption.decrypt(encrypted, node.id)
            return node.withDecryptedKey(plaintext.decodeToString())
        }
        return node
    }

    override suspend fun getNodes(): List<MeilisearchNode> {
        return nodeRepository.findAll().map { decryptNode(it) }
    }

    override suspend fun getNode(id: UUID): MeilisearchNode? {
        return nodeRepository.findById(id)?.let { decryptNode(it) }
    }

    private suspend fun getCachedNode(id: UUID): MeilisearchNode? {
        nodeCache.getIfPresent(id)?.let { return it }
        val node = getNode(id) ?: return null
        nodeCache.put(id, node)
        return node
    }

    override suspend fun getNodeHealth(nodeId: UUID): Boolean {
        val node = getCachedNode(nodeId) ?: error("Node not found: $nodeId")
        return try {
            val nodeClient = getOrCreateNodeClient(node)
            nodeClient.health()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    override suspend fun getNodeVersion(nodeId: UUID): MeilisearchVersion? {
        val node = getCachedNode(nodeId) ?: error("Node not found: $nodeId")
        return try {
            val nodeClient = getOrCreateNodeClient(node)
            val version = nodeClient.getVersion()
            MeilisearchVersion(
                pkgVersion = version.pkgVersion,
                commitDate = version.commitDate,
                commitSha = version.commitSha,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn("Failed to get version for node '{}'", nodeId, e)
            null
        }
    }

    override suspend fun getNodeStats(nodeId: UUID): MeilisearchGlobalStats? {
        val node = getCachedNode(nodeId) ?: error("Node not found: $nodeId")
        return try {
            val nodeClient = getOrCreateNodeClient(node)
            val stats = nodeClient.getStats()
            MeilisearchGlobalStats(
                numberOfIndexes = stats.indexes?.size ?: 0,
                databaseSize = stats.databaseSize,
                lastUpdate = stats.lastUpdate,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn("Failed to get stats for node '{}'", nodeId, e)
            null
        }
    }

    override suspend fun getNodeIndexes(nodeId: UUID): List<String> {
        val node = getCachedNode(nodeId) ?: error("Node not found: $nodeId")
        return try {
            val nodeClient = getOrCreateNodeClient(node)
            nodeClient.getIndexes().results.map { it.uid }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn("Failed to get indexes for node '{}'", nodeId, e)
            emptyList()
        }
    }

    override suspend fun addNode(
        name: String,
        description: String,
        url: String,
        apiKey: String,
        types: List<StorageSystemType>,
    ): MeilisearchNode = transaction {
        val id = Uuid.random()
        val encrypted = encryptApiKey(apiKey, id)
        val node = MeilisearchNode(
            id = id,
            name = name,
            description = description,
            url = url,
            apiKey = encrypted.data,
            apiKeyNonce = encrypted.nonce,
            types = types,
        )
        val saved = nodeRepository.add(node)
        saved.withDecryptedKey(apiKey)
    }

    override suspend fun editNode(
        id: UUID,
        name: String?,
        description: String?,
        url: String?,
        apiKey: String?,
        types: List<StorageSystemType>?,
    ): MeilisearchNode = transaction {
        val existing = nodeRepository.findById(id) ?: error("Node not found: $id")
        val updated = if (apiKey != null) {
            val encrypted = encryptApiKey(apiKey, id)
            existing.copy(
                name = name ?: existing.name,
                description = description ?: existing.description,
                url = url ?: existing.url,
                apiKey = encrypted.data,
                apiKeyNonce = encrypted.nonce,
                types = types ?: existing.types,
            )
        } else {
            existing.copy(
                name = name ?: existing.name,
                description = description ?: existing.description,
                url = url ?: existing.url,
                types = types ?: existing.types,
            )
        }
        val saved = nodeRepository.update(updated)
        nodeCache.invalidate(id)
        nodeClientCache.invalidate(id)
        decryptNode(saved)
    }

    override suspend fun deleteNode(id: UUID): Boolean = transaction {
        val exists = nodeRepository.findById(id) != null
        if (exists) {
            nodeRepository.deleteById(id)
            nodeCache.invalidate(id)
            nodeClientCache.invalidate(id)
        }
        exists
    }

    override suspend fun assignNodeToStorageSystem(storageSystemId: UUID, nodeId: UUID): Boolean = transaction {
        val exists = nodeRepository.findById(nodeId) != null
        if (!exists) return@transaction false
        nodeRepository.assignToStorageSystem(storageSystemId, nodeId)
        true
    }

    override suspend fun removeNodeFromStorageSystem(storageSystemId: UUID, nodeId: UUID): Boolean = transaction {
        val exists = nodeRepository.findById(nodeId) != null
        if (!exists) return@transaction false
        nodeRepository.removeFromStorageSystem(storageSystemId, nodeId)
        true
    }

    override suspend fun getStorageSystemIdsForNode(nodeId: UUID): List<UUID> {
        return nodeRepository.findStorageSystemIdsByNodeId(nodeId)
    }

    // ── Replication ──

    override suspend fun exportToNode(
        nodeId: UUID,
        indexUids: List<String>?,
        overrideSettings: Boolean?,
    ): MeilisearchTask {
        val node = getCachedNode(nodeId) ?: error("Node not found: $nodeId")
        val body = buildJsonObject {
            put("url", node.url)
            put("apiKey", node.decryptedApiKey)
            if (indexUids != null) {
                put("indexUids", kotlinx.serialization.json.JsonArray(indexUids.map { JsonPrimitive(it) }))
            }
            if (overrideSettings == true) {
                put("overrideSettings", true)
            }
        }.toString()
        client.updateExperimentalFeatures(mapOf("exportBatch" to true))
        val taskInfo = client.export(body)
        return MeilisearchTask(
            uid = taskInfo.taskUid,
            type = "export",
            status = taskInfo.status ?: "enqueued",
            enqueuedAt = taskInfo.enqueuedAt ?: "",
        )
    }

    // ── Federation ──

    override suspend fun configureNetwork(nodeId: UUID, remotes: List<MeilisearchNetworkRemoteInput>): Boolean {
        val node = getCachedNode(nodeId) ?: error("Node not found: $nodeId")
        val nodeClient = getOrCreateNodeClient(node)
        // First enable the network feature
        nodeClient.updateExperimentalFeatures(mapOf("network" to true))
        // Then configure the remotes
        val remotesJson = buildJsonObject {
            val remotesObj = buildJsonObject {
                for (remote in remotes) {
                    put(remote.name, buildJsonObject {
                        put("url", remote.url)
                    })
                }
            }
            put("remotes", remotesObj)
        }.toString()
        nodeClient.updateNetwork(remotesJson)
        return true
    }

    override suspend fun getNetworkRemotes(nodeId: UUID): List<MeilisearchNetworkRemote> {
        val node = getCachedNode(nodeId) ?: error("Node not found: $nodeId")
        return try {
            val nodeClient = getOrCreateNodeClient(node)
            val response = nodeClient.getNetwork()
            val element = json.parseToJsonElement(response)
            val remotes = element.jsonObject["remotes"]?.jsonObject ?: return emptyList()
            remotes.entries.map { (name, value) ->
                val url = (value as? JsonObject)?.get("url")?.jsonPrimitive?.content ?: ""
                MeilisearchNetworkRemote(name = name, url = url)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn("Failed to get network remotes for node '{}'", nodeId, e)
            emptyList()
        }
    }

    // ── Helpers ──

    private val nodeClientCache = Caffeine.newBuilder()
        .maximumSize(100)
        .expireAfterWrite(Duration.ofMinutes(5))
        .removalListener<UUID, MeilisearchClient> { _, client, _ ->
            client?.close()
        }
        .build<UUID, MeilisearchClient>()

    private fun getOrCreateNodeClient(node: MeilisearchNode): MeilisearchClient {
        return nodeClientCache.get(node.id) {
            MeilisearchClient(url = node.url, apiKey = node.decryptedApiKey, json = json)
        }
    }

    private fun convertTaskInfo(taskInfo: TaskInfo): MeilisearchTask {
        return MeilisearchTask(
            uid = taskInfo.taskUid,
            type = taskInfo.type ?: "unknown",
            status = taskInfo.status ?: "enqueued",
            indexUid = taskInfo.indexUid,
            enqueuedAt = taskInfo.enqueuedAt ?: "",
        )
    }

    private fun convertTask(task: Task): MeilisearchTask {
        return MeilisearchTask(
            uid = task.uid,
            type = task.type ?: "unknown",
            status = task.status,
            indexUid = task.indexUid,
            enqueuedAt = task.enqueuedAt ?: "",
            startedAt = task.startedAt,
            finishedAt = task.finishedAt,
            duration = task.duration,
            error = task.error?.let { error ->
                MeilisearchTaskError(
                    message = error.message,
                    code = error.code,
                    type = error.type,
                    link = error.link,
                )
            },
        )
    }

    private fun convertSettings(settings: Settings): MeilisearchIndexSettings {
        val embedders = try {
            settings.embedders?.map { (name, embedderJson) ->
                MeilisearchEmbedderEntry(
                    name = name,
                    source = embedderJson["source"]?.jsonPrimitive?.content,
                    model = embedderJson["model"]?.jsonPrimitive?.content,
                    dimensions = embedderJson["dimensions"]?.jsonPrimitive?.content?.toIntOrNull(),
                    documentTemplate = embedderJson["documentTemplate"]?.jsonPrimitive?.content,
                    documentTemplateMaxBytes = embedderJson["documentTemplateMaxBytes"]?.jsonPrimitive?.content?.toIntOrNull(),
                    url = embedderJson["url"]?.jsonPrimitive?.content,
                    request = embedderJson["request"],
                    response = embedderJson["response"],
                    headers = embedderJson["headers"],
                )
            } ?: emptyList()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn("Failed to read embedder settings", e)
            emptyList()
        }

        val synonyms = settings.synonyms?.map { (word, syns) ->
            MeilisearchSynonym(word = word, synonyms = syns)
        } ?: emptyList()

        val typoTolerance = settings.typoTolerance?.let { tt ->
            MeilisearchTypoTolerance(
                enabled = tt.enabled ?: true,
                minWordSizeForTypos = tt.minWordSizeForTypos?.let { mw ->
                    MeilisearchMinWordSize(
                        oneTypo = mw["oneTypo"] ?: 5,
                        twoTypos = mw["twoTypos"] ?: 9,
                    )
                },
                disableOnWords = tt.disableOnWords,
                disableOnAttributes = tt.disableOnAttributes,
            )
        }

        return MeilisearchIndexSettings(
            rankingRules = settings.rankingRules ?: emptyList(),
            searchableAttributes = settings.searchableAttributes ?: emptyList(),
            displayedAttributes = settings.displayedAttributes ?: emptyList(),
            filterableAttributes = settings.filterableAttributes ?: emptyList(),
            sortableAttributes = settings.sortableAttributes ?: emptyList(),
            stopWords = settings.stopWords ?: emptyList(),
            synonyms = synonyms,
            distinctAttribute = settings.distinctAttribute,
            typoTolerance = typoTolerance,
            pagination = settings.pagination?.let { MeilisearchPagination(maxTotalHits = it.maxTotalHits ?: 1000) },
            faceting = settings.faceting?.let { MeilisearchFaceting(maxValuesPerFacet = it.maxValuesPerFacet ?: 100) },
            embedders = embedders,
            proximityPrecision = settings.proximityPrecision,
            searchCutoffMs = settings.searchCutoffMs,
        )
    }
}
