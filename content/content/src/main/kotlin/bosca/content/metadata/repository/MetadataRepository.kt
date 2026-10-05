package bosca.content.metadata.repository

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.SourceStatus
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface MetadataRepository {

    @Query("insert into metadata (parent_id, name, type, content_type, content_length, language_tag, labels, recommendation_contexts, attributes, system_attributes, public, public_content, public_supplementary, created, modified, source_id, source_identifier, source_url, delete_workflow_id, permission_mutation, etag, locked, searchable, recommendable, sync_variant_collections, sync_variant_relationships) values (:parentId, :name, :type, :contentType, :contentLength, :languageTag, :labels, :recommendationContexts, :attributes, :systemAttributes, :public, :publicContent, :publicSupplementary, :created, :modified, :sourceId, :sourceIdentifier, :sourceUrl, :deleteWorkflowId, :permissionMutation, :etag, :locked, :searchable, :recommendable, :syncVariantCollections, :syncVariantRelationships) returning *")
    suspend fun add(metadata: Metadata): Metadata

    @Query("update metadata set active_version = :activeVersion, parent_id = :parentId, name = :name, type = :type, content_type = :contentType, content_length = :contentLength, language_tag = :languageTag, labels = :labels, recommendation_contexts = :recommendationContexts, attributes = :attributes, system_attributes = :systemAttributes, public = :public, public_content = :publicContent, public_supplementary = :publicSupplementary, modified = now(), source_id = :sourceId, source_identifier = :sourceIdentifier, source_url = :sourceUrl, delete_workflow_id = :deleteWorkflowId, permission_mutation = :permissionMutation, etag = :etag, locked = :locked, searchable = :searchable, recommendable = :recommendable, sync_variant_relationships = :syncVariantRelationships, sync_variant_collections = :syncVariantCollections where id = :id and version = :version returning *")
    suspend fun edit(metadata: Metadata): Metadata

    @Query("select * from metadata where id = :id")
    suspend fun getById(id: UUID): Metadata?

    @Query("select * from metadata where id = :id and version = :version")
    suspend fun getById(id: UUID, version: Int): Metadata?

    @Query("update metadata set name = :name, modified = now() where id = :id returning *")
    suspend fun setName(id: UUID, name: String): Metadata?

    @Query("select id from metadata where (parent_id = (select parent_id from metadata where id = :id limit 1) or id = (select parent_id from metadata where id = :id limit 1) or parent_id = :id) and language_tag = :languageTag limit 1")
    suspend fun getLanguageVariantById(id: UUID, languageTag: String): UUID?

    @Query("select * from metadata where deleted = false order by id limit :limit offset :offset")
    suspend fun getAll(offset: Long, limit: Int): List<Metadata>

    @Query("select * from metadata where deleted = true order by id limit :limit offset :offset")
    suspend fun getDeleted(offset: Long, limit: Int): List<Metadata>

    @Query("select * from metadata where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Metadata>

    @Query("select * from metadata where parent_id = :id or id = :id")
    suspend fun getByParentId(id: UUID): List<Metadata>

    @Query("update metadata set deleted = true, modified = now() where id = :id")
    suspend fun markDeleted(id: UUID)

    @Query("update metadata set system_attributes = :attributes, modified = now() where id = :id")
    suspend fun setSystemAttributes(id: UUID, attributes: JsonElement?)

    @Query("update metadata set uploaded = now(), modified = now(), content_type = :contentType, content_length = :contentLength, recommendation_contexts = :recommendationContexts, etag = md5(id::text || '-' || :contentLength::text || '-' || now()::text) where id = :id")
    suspend fun setUploaded(id: UUID, contentType: String, contentLength: Long, recommendationContexts: List<String>)

    @Query("update metadata set source_status = :status, modified = now() where id = :id returning *")
    suspend fun setSourceStatus(id: UUID, status: SourceStatus?): Metadata?

    @Query("update metadata set parent_id = :parentId, modified = now() where id = :id")
    suspend fun setParentId(id: UUID, parentId: UUID?)

    @Query("update metadata set public = :public, modified = now() where id = :id")
    suspend fun setPublic(id: UUID, public: Boolean)

    @Query("update metadata set public_content = :public, modified = now() where id = :id")
    suspend fun setPublicContent(id: UUID, public: Boolean)

    @Query("update metadata set public_supplementary = :public, modified = now() where id = :id")
    suspend fun setPublicSupplementary(id: UUID, public: Boolean)

    @Query("update metadata set locked = :locked, modified = now() where id = :id")
    suspend fun setLocked(id: UUID, locked: Boolean)

    @Query("update metadata set workflow_state_id = :state, workflow_state_pending_id = null, workflow_state_valid = null, modified = now() where id = :id returning *")
    suspend fun setState(id: UUID, state: String): Metadata

    @Query("update metadata set workflow_state_pending_id = :state, workflow_state_valid = :stateValid, modified = now() where id = :id returning *")
    suspend fun setStatePending(id: UUID, state: String, stateValid: OffsetDateTime?): Metadata

    @Query("update metadata set workflow_state_pending_id = null, workflow_state_valid = null, modified = now() where id = :id")
    suspend fun clearPendingState(id: UUID)

    @Query("update metadata set uploaded = null, modified = now(), content_type = null, content_length = null, recommendation_contexts = :recommendationContexts where id = :id")
    suspend fun clearUploaded(id: UUID, recommendationContexts: List<String>)

    @Query("update metadata set ready = now(), modified = now() where id = :id")
    suspend fun setReady(id: UUID)

    @Query("update metadata set ready = null, modified = now() where id = :id")
    suspend fun setNotReady(id: UUID)

    @Query("update metadata set modified = now() where id = :id")
    suspend fun setModified(id: UUID)

    @Query("select attributes from metadata where id = :id")
    suspend fun getAttributes(id: UUID): JsonElement?

    @Query("update metadata set attributes = :attributes, recommendation_contexts = :recommendationContexts, modified = now() where id = :id")
    suspend fun setAttributes(id: UUID, attributes: JsonElement?, recommendationContexts: List<String>)

    @Query("update metadata set recommendation_contexts = :recommendationContexts where id = :id")
    suspend fun setRecommendationContexts(
        id: UUID,
        recommendationContexts: List<String>,
    )

    /** Locks and returns the owning metadata row so replacement can verify source freshness atomically. */
    @Query("select * from metadata where id = :id for no key update")
    suspend fun lockForEmbeddingReplacement(id: UUID): Metadata?

    /** Deletes all semantic embedding chunks currently stored for [id]. */
    @Query("delete from metadata_embeddings where metadata_id = :id")
    suspend fun deleteEmbeddings(id: UUID)

    /** Batch-adds prepared semantic chunks from a JSON array; each embedding uses pgvector text form. */
    @Query("""
        insert into metadata_embeddings (
            metadata_id, chunk_index, token_start, token_end, token_count, aggregation_weight, embedding
        )
        select
            :id,
            chunk.chunk_index,
            chunk.token_start,
            chunk.token_end,
            chunk.token_count,
            chunk.aggregation_weight,
            cast(chunk.embedding as vector)
        from jsonb_to_recordset(:chunks::jsonb) as chunk(
            chunk_index integer,
            token_start integer,
            token_end integer,
            token_count integer,
            aggregation_weight double precision,
            embedding text
        )
    """)
    suspend fun addEmbeddingChunks(id: UUID, chunks: JsonElement)

    /** Returns distinct metadata identifiers having at least one stored embedding chunk. */
    @Query("select distinct metadata_id from metadata_embeddings where metadata_id = any(:ids)")
    suspend fun getEmbeddingIds(ids: List<UUID>): List<UUID>

    @Query("update metadata set searchable = :searchable, modified = now() where id = :id")
    suspend fun setSearchable(id: UUID, searchable: Boolean)

    @Query("update metadata set recommendable = :recommendable, modified = now() where id = :id")
    suspend fun setRecommendable(id: UUID, recommendable: Boolean)

    @Query("update metadata set comments_enabled = :enabled, modified = now() where id = :id")
    suspend fun setCommentsEnabled(id: UUID, enabled: Boolean)

    @Query("update metadata set comment_replies_enabled = :enabled, modified = now() where id = :id")
    suspend fun setCommentRepliesEnabled(id: UUID, enabled: Boolean)

    @Query("update metadata set sync_variant_collections = :syncVariants, modified = now() where id = :id")
    suspend fun setSyncVariantCollections(id: UUID, syncVariants: Boolean)

    @Query("update metadata set sync_variant_relationships = :syncVariants, modified = now() where id = :id")
    suspend fun setSyncVariantRelationships(id: UUID, syncVariants: Boolean)

    @Query("update metadata set attributes = attributes || :attributes, modified = now() where id = :id")
    suspend fun mergeAttributes(id: UUID, attributes: JsonElement?)

    @Query("delete from metadata where id = :id")
    suspend fun deleteById(id: UUID)
}
