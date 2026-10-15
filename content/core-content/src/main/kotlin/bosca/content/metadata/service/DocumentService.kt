package bosca.content.metadata.service

import bosca.content.metadata.model.Document
import bosca.content.metadata.model.DocumentCollaboration
import bosca.content.metadata.model.DocumentCollaborationInput
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.Serializable

/**
 * Controls how a `setDocument` call interacts with the collaboration (Yjs CRDT) state.
 *
 * The collaboration row and the document row hold the same content in different
 * representations and are written by separate paths (the Hocuspocus-backed editor
 * updates collaboration; everything else updates the document). When a non-editor
 * caller writes the document, the collaboration row may go stale and silently
 * overwrite the new content the next time the editor opens. This enum lets callers
 * pick how to keep them coherent.
 *
 * - [NONE] (default): leave collaboration alone. Used by the editor save flow,
 *   which has already PUT a fresh CRDT update separately.
 * - [RESET]: delete the collaboration row. **Client contract:** when the editor
 *   loads and finds no collaboration row (404 / empty body), it must seed a fresh
 *   Yjs doc from the document content fetched via GraphQL and PUT it back. The
 *   `useCollaborationAndAttributes` composable in `web/projects/studio` and
 *   `web/projects/administration` already does this via `prosemirrorJSONToYXmlFragment`;
 *   any other client that loads collaboration must do the same. Loses any in-flight
 *   unsaved collaboration edits at the moment of the write.
 * - [MERGE]: diff-merge the new content into the existing collaboration CRDT.
 *   Subtrees that match the existing fragment bit-for-bit are left as untouched Yjs
 *   items, so concurrent in-flight editor edits inside those regions survive the
 *   merge unchanged. Only regions that actually differ get delete/insert operations.
 *   Non-document Yjs maps (collections-dirty flags, awareness, attribute maps) are
 *   never touched. Implemented via [bosca.documents.yjs.ProseMirrorYjsBridge].
 *   The diff is shallow at each level — when an old/new pair differs, the entire
 *   subtree is replaced rather than recursively diffed; concurrent edits inside a
 *   replaced subtree are still lost.
 */
@Serializable
enum class CollaborationSyncMode { NONE, RESET, MERGE }

/**
 * Service for managing document content associated with metadata entries. Documents represent
 * rich, template-driven content (e.g., articles, pages) that can be collaboratively edited
 * and versioned alongside their parent metadata.
 */
interface DocumentService : Service {

    /**
     * Invalidates cached document data for a specific metadata identifier and optional version.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version to remove from cache, or null to remove all versions
     */
    suspend fun removeFromCache(id: UUID, version: Int?)

    /**
     * Retrieves a document by its parent metadata identifier and version.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @return the document, or null if not found
     */
    suspend fun getDocument(id: UUID, version: Int): Document?

    /**
     * Registers a batch loader for efficiently fetching documents by metadata cache key.
     *
     * @param batch the batch accumulator to populate with document data
     */
    suspend fun getDocumentsBatch(batch: Batch<MetadataCacheKeyId, Document>)

    /**
     * Creates a new document for the specified metadata version.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @param document the document definition to persist
     */
    suspend fun addDocument(id: UUID, version: Int, document: DocumentInput)

    /**
     * Creates or replaces the document associated with a metadata entry, using the
     * metadata's current version.
     *
     * @param metadata the parent metadata entry
     * @param document the document definition to persist
     * @param collaborationSync how to synchronize the collaboration CRDT row with this
     *  write. Defaults to [CollaborationSyncMode.NONE] so editor saves (which manage
     *  collaboration on their own) are never disturbed.
     */
    suspend fun setDocument(
        metadata: Metadata,
        document: DocumentInput,
        collaborationSync: CollaborationSyncMode = CollaborationSyncMode.NONE,
    )

    /**
     * Assigns a document template to a metadata entry's document, defining its structure
     * and schema.
     *
     * @param metadata the parent metadata entry
     * @param templateId the template's metadata identifier
     * @param templateVersion the template's version number
     */
    suspend fun setDocumentTemplate(metadata: Metadata, templateId: UUID, templateVersion: Int)

    /**
     * Retrieves the collaboration configuration for a document.
     *
     * @param metadataId the parent metadata identifier
     * @param version the metadata version number
     * @return the collaboration configuration, or null if none exists
     */
    suspend fun getCollaboration(metadataId: UUID, version: Int): DocumentCollaboration?

    /**
     * Marks the collaboration collections data as dirty for a metadata entry, triggering
     * re-synchronization of collaborative collection data.
     *
     * @param metadataId the metadata identifier
     */
    suspend fun markCollaborationCollectionsDirty(metadataId: UUID)

    /**
     * Marks the collaboration relationships data as dirty for a metadata entry, triggering
     * re-synchronization of collaborative relationship data.
     *
     * @param metadataId the metadata identifier
     */
    suspend fun markCollaborationRelationshipsDirty(metadataId: UUID)

    /**
     * Marks the collaboration attributes data as dirty for a metadata entry, triggering
     * re-synchronization of collaborative attribute data.
     *
     * @param metadataId the metadata identifier
     */
    suspend fun markCollaborationAttributesDirty(metadataId: UUID)

    /**
     * Creates or updates the collaboration configuration for a document.
     *
     * @param collaboration the collaboration configuration to persist
     */
    suspend fun setCollaboration(collaboration: DocumentCollaborationInput): Boolean

    /**
     * Removes the collaboration configuration from a document.
     *
     * @param metadataId the parent metadata identifier
     * @param version the metadata version number
     */
    suspend fun removeCollaboration(metadataId: UUID, version: Int)
}