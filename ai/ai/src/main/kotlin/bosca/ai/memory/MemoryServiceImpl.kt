package bosca.ai.memory

import bosca.ai.chat.service.ChatHistoryService
import bosca.content.collection.service.CollectionService
import bosca.content.find.FindAttributeInput
import bosca.content.find.FindAttributesInput
import bosca.content.find.FindQueryInput
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.MemoryAttributes
import bosca.content.metadata.model.MemoryScope
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.service.CollaborationSyncMode
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.ordering.Order
import bosca.content.ordering.OrderingInput
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.transaction
import bosca.documents.Content
import bosca.documents.DocumentNode
import bosca.documents.ParagraphNode
import bosca.documents.TextNode
import bosca.search.model.SearchFilter
import bosca.search.model.SearchQuery
import bosca.search.service.SearchService
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@ServiceImplementation
class MemoryServiceImpl(
    private val metadata: MetadataService,
    private val documents: DocumentService,
    private val collections: CollectionService,
    private val permissions: MetadataPermissionEvaluator,
    private val collectionPermissions: CollectionPermissionEvaluator,
    private val security: SecurityService,
    private val groups: GroupEvaluator,
    private val history: ChatHistoryService,
    private val searchService: SearchService,
    private val saves: MemorySaveRepository,
    private val json: Json,
) : MemoryService {
    override suspend fun recordRead(authentication: AuthenticationContext, sessionId: UUID, memory: Memory) {
        verifyConversation(authentication, sessionId)
        saves.recordRead(sessionId, memory.id, memory.revision, memory.sourceChanged)
    }

    override suspend fun reads(authentication: AuthenticationContext, sessionId: UUID): List<MemoryRead> {
        verifyConversation(authentication, sessionId)
        return saves.reads(sessionId)
    }

    override suspend fun clearReads(sessionId: UUID) = saves.clearReads(sessionId)

    private suspend fun verifyConversation(authentication: AuthenticationContext, sessionId: UUID) {
        val principal = requireNotNull(authentication.principal()) { "Not authenticated" }
        require(history.getSession(sessionId)?.principalId == principal.id) { "Conversation is unavailable" }
    }

    override suspend fun remember(authentication: AuthenticationContext, input: RememberMemoryInput): Memory = transaction {
        groups.verifyHasScope(authentication, "content:edit")
        val principal = requireNotNull(authentication.principal()) { "Not authenticated" }
        validateStatement(input.statement)
        require(input.tags.size <= 10 && input.tags.all { it.length in 1..64 }) { "Use at most 10 short tags" }
        val digest = hash(json.encodeToString(RememberMemoryInput.serializer(), input))
        saves.claim(principal.id, input.requestId, digest)
        val receipt = requireNotNull(saves.getForUpdate(principal.id, input.requestId))
        require(receipt.inputDigest == digest) { "This request ID was already used for a different memory" }
        receipt.metadataId?.let { saved ->
            val memory = requireNotNull(get(authentication, saved)) { "The saved memory is no longer available; it was not recreated" }
            // A retry returns the saved memory even after its conversation was deleted.
            input.conversationId?.takeIf { history.getSession(it)?.principalId == principal.id }?.let {
                saves.recordRead(it, memory.id, memory.revision, memory.sourceChanged)
            }
            return@transaction memory
        }
        require(input.expires?.isAfter(OffsetDateTime.now()) != false) { "Memory expiry must be in the future" }
        input.conversationId?.let {
            require(history.getSession(it)?.principalId == principal.id) { "Conversation is unavailable" }
        }
        val parent = if (input.scope == MemoryScope.COLLECTION) {
            val collection = requireNotNull(input.collectionId?.let { collections.getById(it) }) { "Select a collection for a shared memory" }
            require(!collection.deleted) { "Collection is unavailable" }
            collectionPermissions.verifyAllowed(authentication, collection, PermissionAction.VIEW)
            collectionPermissions.verifyAllowed(authentication, collection, PermissionAction.EDIT)
            collection
        } else {
            require(input.collectionId == null) { "Personal memories do not inherit collection sharing" }
            null
        }
        val personalGroup = if (input.scope == MemoryScope.PERSONAL) {
            val group = requireNotNull(security.getGroupByName("${principal.id}.user", GroupType.PRINCIPAL)) { "The account's private group is unavailable" }
            require(principal.hasGroup(group)) { "This credential does not include the account's private group" }
            group
        } else null
        val source = input.sourceMetadataId?.let { source(authentication, it) }
        val provenance = MemoryAttributes(input.scope, principal.id, input.collectionId, input.sourceMetadataId,
            source?.first, source?.second, input.conversationId, input.expires)
        val created = metadata.add(parent, null, MetadataInput(
            name = input.statement.take(120), languageTag = "en", contentType = MemoryAttributes.CONTENT_TYPE,
            parentCollectionId = parent?.id, document = document(input.statement),
            traitIds = listOf(MemoryAttributes.TRAIT), labels = input.tags, recommendable = false,
        ))
        val attributes = json.encodeToJsonElement(MemoryAttributes.serializer(), provenance)
        metadata.setSystemAttributes(created, attributes)
        personalGroup?.let { group ->
            for (action in listOf(PermissionAction.VIEW, PermissionAction.EDIT, PermissionAction.DELETE, PermissionAction.MANAGE)) {
                metadata.addPermission(PermissionInput(action = action, entityId = created.id, groupId = group.id))
            }
        }
        saves.complete(principal.id, input.requestId, created.id)
        project(created.copy(systemAttributes = attributes), document(input.statement).toDocument(created), provenance, false).also { memory ->
            input.conversationId?.let { saves.recordRead(it, memory.id, memory.revision, memory.sourceChanged) }
        }
    }

    override suspend fun get(authentication: AuthenticationContext, id: UUID): Memory? {
        val item = metadata.getById(id) ?: return null
        if (item.deleted || !permissions.isAllowed(authentication, item, PermissionAction.VIEW) ||
            !permissions.isContentAllowed(authentication, item, PermissionAction.VIEW)) return null
        val provenance = MemoryAttributes.from(item) ?: return null
        if (!isVisibleScope(authentication, provenance)) return null
        if (provenance.expires?.isAfter(OffsetDateTime.now()) == false) return null
        if (provenance.scope == MemoryScope.COLLECTION) {
            val collection = provenance.collectionId?.let { collections.getById(it) } ?: return null
            if (collection.deleted || !collectionPermissions.isAllowed(authentication, collection, PermissionAction.VIEW)) return null
        }
        val sourceRevision = provenance.sourceMetadataId?.let { sourceId ->
            val source = metadata.getById(sourceId) ?: return null
            if (source.deleted || source.contentType == MemoryAttributes.CONTENT_TYPE ||
                !permissions.isAllowed(authentication, source, PermissionAction.VIEW) ||
                !permissions.isContentAllowed(authentication, source, PermissionAction.VIEW)) return null
            sourceRevision(source)
        }
        val doc = documents.getDocument(id, item.version) ?: return null
        val changed = sourceRevision?.let { it.second != provenance.sourceRevision } ?: false
        return project(item, doc, provenance, changed)
    }

    override suspend fun search(authentication: AuthenticationContext, query: MemoryQuery): List<Memory> {
        require(query.limit in 1..20 && query.offset in 0..10_000) { "Memory limit must be 1–20 and offset 0–10000" }
        require(query.ids.size <= 20 && query.collectionIds.size <= 16 && query.tags.size <= 10 && query.text.length <= 500) { "Memory selection is too large" }
        val principal = requireNotNull(authentication.principal()) { "Not authenticated" }
        // Offset and limit apply to visible, relevant matches; raw candidates are scanned from the start.
        val wanted = query.offset.toInt() + query.limit
        val seen = mutableSetOf<UUID>()
        val matches = mutableListOf<Memory>()
        suspend fun consider(ids: List<UUID>) {
            for (id in ids) {
                if (matches.size >= wanted) return
                if (!seen.add(id)) continue
                val memory = get(authentication, id) ?: continue
                val relevant = id in query.ids || memory.provenance.collectionId in query.collectionIds ||
                    query.personal && memory.provenance.scope == MemoryScope.PERSONAL && memory.provenance.ownerId == principal.id
                if (relevant && memory.tags.containsAll(query.tags)) matches.add(memory)
            }
        }
        suspend fun scan(fetch: suspend (offset: Long) -> List<UUID>) {
            var offset = 0L
            while (matches.size < wanted && offset < MAX_SCANNED_CANDIDATES) {
                val batch = fetch(offset)
                consider(batch)
                if (batch.size < SCAN_BATCH) return
                offset += batch.size
            }
        }
        consider(query.ids)
        if (query.text.isNotBlank()) {
            scan { offset ->
                searchService.search(SearchQuery(query.text, offset.toInt(), SCAN_BATCH,
                    filter = listOf(SearchFilter.eq("contentType", MemoryAttributes.CONTENT_TYPE).toFilterString()),
                    storageSystemName = "Admin Search Index")).documents.mapNotNull { it.metadata?.id }
            }
        } else {
            val filters = mutableListOf<Pair<String, UUID>>()
            if (query.personal) filters.add("ownerId" to principal.id)
            filters.addAll(query.collectionIds.distinct().map { "collectionId" to it })
            for ((key, id) in filters) {
                scan { offset ->
                    metadata.findBySystem(FindQueryInput(
                        contentTypes = listOf(MemoryAttributes.CONTENT_TYPE),
                        attributes = listOf(FindAttributesInput(listOf(FindAttributeInput(key, id.toString())))),
                        ordering = listOf(OrderingInput(field = "modified", order = Order.DESCENDING), OrderingInput(field = "id", order = Order.ASCENDING)),
                        offset = offset, limit = SCAN_BATCH,
                    )).map { it.id }
                }
            }
        }
        return matches.drop(query.offset.toInt()).take(query.limit)
    }

    override suspend fun update(authentication: AuthenticationContext, id: UUID, expectedRevision: String, statement: String, refreshSource: Boolean): Memory = transaction {
        validateStatement(statement)
        val item = requireNotNull(metadata.getById(id)) { "Memory is unavailable" }
        permissions.verifyAllowed(authentication, item, PermissionAction.EDIT)
        val previous = requireNotNull(get(authentication, id)) { "Memory is unavailable" }
        val draft = requireNotNull(documents.getDocument(id, item.version)) { "Memory is unavailable" }
        require(previous.revision == expectedRevision && revision(item, draft) == expectedRevision) { "Memory changed; read it again before editing" }
        var provenance = previous.provenance
        if (refreshSource) provenance.sourceMetadataId?.let {
            val current = source(authentication, it)
            provenance = provenance.copy(sourceVersion = current.first, sourceRevision = current.second)
        }
        metadata.setDocument(item, document(statement), draft, CollaborationSyncMode.MERGE)
        var saved = requireNotNull(metadata.setName(item.id, statement.take(120)))
        if (provenance != previous.provenance) {
            val attributes = json.encodeToJsonElement(MemoryAttributes.serializer(), provenance)
            metadata.setSystemAttributes(saved, attributes)
            saved = saved.copy(systemAttributes = attributes)
        }
        project(saved, document(statement).toDocument(saved), provenance, !refreshSource && previous.sourceChanged)
    }

    override suspend fun forget(authentication: AuthenticationContext, id: UUID, expectedRevision: String): Boolean = transaction {
        val item = requireNotNull(metadata.getById(id)) { "Memory is unavailable" }
        val provenance = requireNotNull(MemoryAttributes.from(item)) { "Memory is unavailable" }
        require(isVisibleScope(authentication, provenance)) { "Memory is unavailable" }
        permissions.verifyAllowed(authentication, item, PermissionAction.DELETE)
        val draft = requireNotNull(documents.getDocument(id, item.version)) { "Memory is unavailable" }
        require(revision(item, draft) == expectedRevision) { "Memory changed; read it again before forgetting" }
        metadata.markDocumentDeleted(item, draft)
        true
    }

    /** Personal memories belong to their owner alone; role-based metadata access does not extend to them. */
    private fun isVisibleScope(authentication: AuthenticationContext, provenance: MemoryAttributes): Boolean =
        provenance.scope != MemoryScope.PERSONAL || provenance.ownerId == authentication.principal()?.id

    private suspend fun source(authentication: AuthenticationContext, id: UUID): Pair<Int, String> {
        val item = requireNotNull(metadata.getById(id)) { "Source is unavailable" }
        require(!item.deleted && item.contentType != MemoryAttributes.CONTENT_TYPE) { "Source must be current content, not a memory" }
        permissions.verifyAllowed(authentication, item, PermissionAction.VIEW)
        permissions.verifyContentAllowed(authentication, item, PermissionAction.VIEW)
        return sourceRevision(item)
    }

    private suspend fun sourceRevision(item: Metadata): Pair<Int, String> {
        val doc = documents.getDocument(item.id, item.version)
        return item.version to hash("${item.version}:${item.modified}:${item.etag}:" + (doc?.let { json.encodeToString(Document.serializer(), it) } ?: ""))
    }

    private fun project(item: Metadata, doc: Document, provenance: MemoryAttributes, sourceChanged: Boolean) = Memory(
        item.id, item.version, revision(item, doc), text(doc.content?.document), provenance, sourceChanged, item.labels, item.created, item.modified)

    private fun revision(item: Metadata, doc: Document): String {
        // JSONB does not retain object-key order; serialize the typed provenance for a stable revision.
        val provenance = json.encodeToString(MemoryAttributes.serializer(), requireNotNull(MemoryAttributes.from(item)))
        return hash("${item.version}:$provenance:" + json.encodeToString(Document.serializer(), doc))
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    private fun document(statement: String) = DocumentInput(title = "Memory", content = Content(bosca.documents.Document(
        content = listOf(ParagraphNode(content = listOf(TextNode(text = statement)))))))

    private fun text(node: DocumentNode?): String = buildString {
        fun visit(node: DocumentNode) {
            if (length >= 2000) return
            if (node is TextNode) append(node.text.take(2000 - length))
            else { node.content.forEach(::visit); if (length < 2000) append('\n') }
        }
        node?.let(::visit)
    }.trim()

    private fun validateStatement(statement: String) {
        require(statement.isNotBlank() && statement.length <= 2000) { "Use a concise memory statement of at most 2000 characters" }
        require(!CREDENTIAL.containsMatchIn(statement)) { "Credentials cannot be saved as memories" }
    }

    companion object {
        private const val SCAN_BATCH = 100
        /** Raw candidates examined per source; matches beyond this window are not returned. */
        private const val MAX_SCANNED_CANDIDATES = 2_000L
        private val CREDENTIAL = Regex("(?i)(-----BEGIN [A-Z ]*PRIVATE KEY-----|\\bBearer\\s+\\S+|\\bbsk_[A-Za-z0-9_-]+|\\bsk-[A-Za-z0-9_-]{12,}|\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+|\\b(?:password|api[_ -]?key|access[_ -]?token|refresh[_ -]?token|secret)\\s*[:=]\\s*\\S+)")
    }
}
