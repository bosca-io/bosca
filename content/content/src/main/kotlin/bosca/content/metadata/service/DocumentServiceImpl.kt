package bosca.content.metadata.service

import bosca.cache.ServiceCache
import bosca.content.collaboration.Updater
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.DocumentCollaboration
import bosca.content.metadata.model.DocumentCollaborationInput
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataCacheKeySerializer
import bosca.content.metadata.repository.DocumentCollaborationRepository
import bosca.content.metadata.repository.DocumentRepository
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.documents.Content
import bosca.documents.yjs.ProseMirrorYjsBridge
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

@ServiceImplementation
class DocumentServiceImpl(
    private val metadataService: ObjectProvider<MetadataService>,
    private val documentRepository: DocumentRepository,
    private val documentCollaborationRepository: DocumentCollaborationRepository,
    private val contentEntityLinkService: ContentEntityLinkService,
    private val json: Json,
) : DocumentService {

    private val documentCache = ServiceCache<MetadataCacheKeyId, Document>(
        "documents",
        MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val documents = try {
                documentRepository.getByMetadataIdAndVersions(keys.map { it.id })
            } catch (_: SerializationException) {
                // One malformed row prevents the repository from returning any of the batch.
                keys.distinct().mapNotNull { loadDocument(it.id, it.version ?: 1) }
            }.associateBy { MetadataCacheKeyId(it.metadataId, it.version) }
            keys.map { key ->
                documents[key]?.let { batch.setData(key, it) }
            }
        }
    ) {
        loadDocument(it.id, it.version ?: 1)
    }

    private suspend fun loadDocument(id: UUID, version: Int): Document? = try {
        documentRepository.getByMetadataIdAndVersion(id, version)
    } catch (failure: SerializationException) {
        val document = documentRepository.getByMetadataIdAndVersionWithoutContent(id, version) ?: throw failure
        val raw = documentRepository.getRawContent(id, version) ?: throw failure
        val normalizer = LegacySuperscriptNormalizer()
        val normalized = normalizer.normalize(raw)
        if (normalizer.replacements == 0) throw failure
        val content = json.decodeFromJsonElement(Content.serializer(), normalized)
        log.warn(
            "Normalized {} legacy superscript node(s) while reading document {} version {}",
            normalizer.replacements,
            id,
            version,
        )
        Document(
            document.metadataId,
            document.version,
            document.templateMetadataId,
            document.templateMetadataVersion,
            document.title,
            content,
        )
    }

    override suspend fun removeFromCache(id: UUID, version: Int?) = documentCache.remove(MetadataCacheKeyId(id, version))

    override suspend fun getDocument(id: UUID, version: Int) = documentCache.get(MetadataCacheKeyId(id, version))

    override suspend fun getDocumentsBatch(batch: Batch<MetadataCacheKeyId, Document>) = documentCache.addToBatch(batch)

    override suspend fun addDocument(id: UUID, version: Int, document: DocumentInput) {
        documentRepository.add(document.toDocument(id, version))
        document.content?.let { contentEntityLinkService.extractAndStore(id, version, it) }
    }

    override suspend fun setDocument(
        metadata: Metadata,
        document: DocumentInput,
        collaborationSync: CollaborationSyncMode,
    ) {
        val doc = document.toDocument(metadata)
        documentRepository.add(doc.metadataId, doc.version, doc.title, doc.content)
        documentCache.remove(MetadataCacheKeyId(metadata.id, metadata.version))
        doc.content?.let { contentEntityLinkService.extractAndStore(metadata.id, metadata.version, it) }
        when (collaborationSync) {
            CollaborationSyncMode.NONE -> Unit
            CollaborationSyncMode.RESET -> {
                // Drop the stale CRDT row. The next editor open will start from an empty Yjs
                // doc and seed itself from the document we just wrote.
                documentCollaborationRepository.removeCollaboration(metadata.id, metadata.version)
            }
            CollaborationSyncMode.MERGE -> {
                // Diff-merge the new content into the existing collaboration CRDT. Subtrees
                // that match the existing fragment bit-for-bit are left as untouched Yjs
                // items so concurrent in-flight editor edits inside those regions survive;
                // only the regions that actually differ get delete/insert operations.
                // Non-default Yjs maps (collections-dirty flags, awareness markers, attribute
                // maps) are never touched.
                //
                // The read uses `getByMetadataIdAndVersionForUpdate` so concurrent MERGE writers
                // serialize on the row's `FOR UPDATE` lock — without it, two callers that read
                // the same baseline would both compute their own merge and the second
                // commit would silently overwrite the first, losing whichever non-document
                // Yjs maps the loser had updated. The lock is held until the surrounding
                // transaction (entered in `MetadataServiceImpl.setDocument`) commits.
                val newContent = document.content ?: Content()
                val existing = documentCollaborationRepository.getByMetadataIdAndVersionForUpdate(metadata.id, metadata.version)
                val updated = if (existing == null || existing.content.isEmpty()) {
                    ProseMirrorYjsBridge.toYDocUpdate(newContent)
                } else {
                    ProseMirrorYjsBridge.mergeDocument(existing.content, newContent)
                }
                documentCollaborationRepository.setCollaboration(
                    DocumentCollaboration(metadata.id, metadata.version, updated)
                )
            }
        }
    }

    override suspend fun setDocumentTemplate(metadata: Metadata, templateId: UUID, templateVersion: Int) {
        if (getDocument(metadata.id, metadata.version) == null) {
            setDocument(metadata, DocumentInput(title = metadata.name, content = Content()))
        }
        documentRepository.setTemplate(metadata.id, metadata.version, templateId, templateVersion)
        documentCache.remove(MetadataCacheKeyId(metadata.id, metadata.version))
    }

    override suspend fun getCollaboration(metadataId: UUID, version: Int): DocumentCollaboration? {
        return documentCollaborationRepository.getByMetadataIdAndVersion(metadataId, version)
    }

    suspend fun getCollaborationForUpdate(metadataId: UUID, version: Int): DocumentCollaboration? {
        return documentCollaborationRepository.getByMetadataIdAndVersionForUpdate(metadataId, version)
    }

    override suspend fun markCollaborationCollectionsDirty(metadataId: UUID): Unit = transaction {
        log.warn("marking collaboration collections dirty for metadata: $metadataId")
        val metadata = metadataService.get().getById(metadataId) ?: return@transaction
        val collaboration = getCollaborationForUpdate(metadata.id, metadata.version) ?: return@transaction
        val content = Updater().use {
            val content = it.setCollectionsDirty(collaboration.content)
            if (!it.areCollectionsDirty(content)) error("collections are not dirty")
            content
        }
        setCollaboration(DocumentCollaborationInput(metadata.id, metadata.version, content))
    }

    override suspend fun markCollaborationRelationshipsDirty(metadataId: UUID): Unit = transaction {
        log.warn("marking collaboration relationships dirty for metadata: $metadataId")
        val metadata = metadataService.get().getById(metadataId) ?: return@transaction
        val collaboration = getCollaborationForUpdate(metadata.id, metadata.version) ?: return@transaction
        val content = Updater().use {
            val content = it.setRelationshipsDirty(collaboration.content)
            if (!it.areRelationshipsDirty(content)) error("relationships are not dirty")
            content
        }
        setCollaboration(DocumentCollaborationInput(metadata.id, metadata.version, content))
    }

    override suspend fun markCollaborationAttributesDirty(metadataId: UUID): Unit = transaction {
        log.warn("marking collaboration attributes dirty for metadata: $metadataId")
        val metadata = metadataService.get().getById(metadataId) ?: return@transaction
        val collaboration = getCollaborationForUpdate(metadata.id, metadata.version) ?: return@transaction
        val content = Updater().use {
            val content = it.setAttributesDirty(collaboration.content)
            if (!it.areAttributesDirty(content)) error("attributes are not dirty")
            content
        }
        setCollaboration(DocumentCollaborationInput(metadata.id, metadata.version, content))
    }

    override suspend fun setCollaboration(collaboration: DocumentCollaborationInput): Boolean {
        if (getDocument(collaboration.metadataId, collaboration.version) == null) {
            return false
        }
        documentCollaborationRepository.setCollaboration(
            DocumentCollaboration(
                collaboration.metadataId,
                collaboration.version,
                collaboration.content ?: ByteArray(0)
            )
        )
        return true
    }

    override suspend fun removeCollaboration(metadataId: UUID, version: Int) {
        documentCollaborationRepository.removeCollaboration(metadataId, version)
    }

    /** Converts the legacy superscript wrapper node into marks during a failed document read. */
    private class LegacySuperscriptNormalizer {
        var replacements: Int = 0
            private set

        fun normalize(element: JsonElement, path: String = "$"): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { (key, value) ->
                if (key == "content" && value is JsonArray && nodeType(element) != null) {
                    normalizeChildren(value, "$path.content")
                } else {
                    normalize(value, "$path.$key")
                }
            })
            is JsonArray -> JsonArray(element.mapIndexed { index, value -> normalize(value, "$path[$index]") })
            else -> element
        }

        private fun normalizeChildren(content: JsonArray, path: String): JsonArray = JsonArray(
            content.flatMapIndexed { index, child ->
                val childPath = "$path[$index]"
                val node = child as? JsonObject
                if (node != null && nodeType(node) == "superscript") {
                    normalizeSuperscript(node, childPath)
                } else {
                    listOf(normalize(child, childPath))
                }
            },
        )

        private fun normalizeSuperscript(node: JsonObject, path: String): List<JsonElement> {
            val unsupported = node.keys - setOf("type", "attrs", "content", "marks")
            require(unsupported.isEmpty()) {
                "Legacy superscript node at $path has unsupported fields: ${unsupported.sorted().joinToString()}"
            }
            val attributes = node["attrs"]
            require(attributes == null || attributes == JsonNull || attributes == JsonObject(emptyMap())) {
                "Legacy superscript node at $path has attributes that cannot be moved safely"
            }
            val content = node["content"] as? JsonArray
                ?: error("Legacy superscript node at $path has no content array")
            require(content.isNotEmpty()) { "Legacy superscript node at $path has an empty content array" }

            val inheritedMarks = readMarks(node["marks"], "$path.marks") +
                JsonObject(mapOf("type" to JsonPrimitive("superscript")))
            var markedTextNodes = 0
            val flattened = normalizeChildren(content, "$path.content").mapIndexed { index, child ->
                val (marked, count) = applyMarks(child, inheritedMarks, "$path.content[$index]")
                markedTextNodes += count
                marked
            }
            require(markedTextNodes > 0) { "Legacy superscript node at $path contains no text node to mark" }
            replacements++
            return flattened
        }

        private fun applyMarks(
            element: JsonElement,
            inheritedMarks: List<JsonObject>,
            path: String,
        ): Pair<JsonElement, Int> {
            val node = element as? JsonObject ?: return element to 0
            if (nodeType(node) == "text") {
                val existing = readMarks(node["marks"], "$path.marks")
                val types = existing.mapNotNullTo(mutableSetOf()) { nodeType(it) }
                val additions = inheritedMarks.filter { mark ->
                    val type = nodeType(mark) ?: error("Inherited mark at $path has no string type")
                    types.add(type)
                }
                return JsonObject(node + ("marks" to JsonArray(existing + additions))) to 1
            }
            val content = node["content"] as? JsonArray ?: return element to 0
            var markedTextNodes = 0
            val markedContent = JsonArray(content.mapIndexed { index, child ->
                val (marked, count) = applyMarks(child, inheritedMarks, "$path.content[$index]")
                markedTextNodes += count
                marked
            })
            return JsonObject(node + ("content" to markedContent)) to markedTextNodes
        }

        private fun readMarks(value: JsonElement?, path: String): List<JsonObject> {
            if (value == null || value == JsonNull) return emptyList()
            val marks = value as? JsonArray ?: error("Expected a marks array at $path")
            return marks.mapIndexed { index, mark ->
                mark as? JsonObject ?: error("Expected a mark object at $path[$index]")
            }
        }

        private fun nodeType(node: JsonObject): String? =
            (node["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }

    companion object {

        private val log = LoggerFactory.getLogger(this::class.java)
    }
}
