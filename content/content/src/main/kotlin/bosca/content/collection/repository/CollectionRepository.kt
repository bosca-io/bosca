package bosca.content.collection.repository

import bosca.content.collection.model.Collection
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface CollectionRepository {

    @Query("insert into collections (id, name, type, description, attributes, system_attributes, labels, recommendation_contexts, created, modified, ready, etag, enabled, ordering, delete_workflow_id, public, public_list, public_supplementary, locked, items_locked, template_metadata_id, template_metadata_version, searchable, recommendable) values ('00000000-0000-0000-0000-000000000000', :name, :type, :description, :attributes, :systemAttributes, :labels, :recommendationContexts, :created, :modified, :ready, :etag, :enabled, :ordering, :deleteWorkflowId, :public, :publicList, :publicSupplementary, :locked, :itemsLocked, :templateMetadataId, :templateMetadataVersion, :searchable, :recommendable) returning *")
    suspend fun addRoot(collection: Collection): Collection

    @Query("insert into collections (name, type, description, attributes, system_attributes, labels, recommendation_contexts, created, modified, ready, etag, enabled, ordering, delete_workflow_id, public, public_list, public_supplementary, locked, items_locked, template_metadata_id, template_metadata_version, searchable, recommendable) values (:name, :type, :description, :attributes, :systemAttributes, :labels, :recommendationContexts, :created, :modified, :ready, :etag, :enabled, :ordering, :deleteWorkflowId, :public, :publicList, :publicSupplementary, :locked, :itemsLocked, :templateMetadataId, :templateMetadataVersion, :searchable, :recommendable) returning *")
    suspend fun add(collection: Collection): Collection

    @Query("update collections set name = :name, type = :type, description = :description, attributes = :attributes, system_attributes = :systemAttributes, labels = :labels, recommendation_contexts = :recommendationContexts, modified = now(), etag = :etag, enabled = :enabled, ordering = :ordering, delete_workflow_id = :deleteWorkflowId, public = :public, public_list = :publicList, public_supplementary = :publicSupplementary, searchable = :searchable, recommendable = :recommendable, locked = :locked, items_locked = :itemsLocked, template_metadata_id = :templateMetadataId, template_metadata_version = :templateMetadataVersion where id = :id returning *")
    suspend fun update(collection: Collection): Collection

    @Query("select * from collections where deleted = false order by id offset :offset limit :limit")
    suspend fun getAll(offset: Long, limit: Int): List<Collection>

    @Query("select * from collections where deleted = true order by id offset :offset limit :limit")
    suspend fun getDeleted(offset: Long, limit: Int): List<Collection>

    @Query("select * from collections where id = :id")
    suspend fun getById(id: UUID): Collection?

    @Query("select * from collections where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Collection>

    @Query("update collections set deleted = true, modified = now() where id = :id")
    suspend fun markDeleted(id: UUID)

    @Query("update collections set attributes = :attributes, recommendation_contexts = :recommendationContexts, modified = now() where id = :id")
    suspend fun setAttributes(id: UUID, attributes: JsonElement?, recommendationContexts: List<String>)

    @Query("update collections set attributes = attributes || (:attributes)::jsonb, modified = now() where id = :id returning *")
    suspend fun mergeAttributes(id: UUID, attributes: JsonElement?): Collection

    @Query("update collections set recommendation_contexts = :recommendationContexts where id = :id")
    suspend fun setRecommendationContexts(
        id: UUID,
        recommendationContexts: List<String>,
    )

    @Query("update collections set system_attributes = :attributes, modified = now() where id = :id")
    suspend fun setSystemAttributes(id: UUID, attributes: JsonElement?)

    @Query("update collections set locked = :locked, modified = now() where id = :id")
    suspend fun setLocked(id: UUID, locked: Boolean)

    @Query("update collections set public = :public, modified = now() where id = :id")
    suspend fun setPublic(id: UUID, public: Boolean)

    @Query("update collections set public_list = :public, modified = now() where id = :id")
    suspend fun setPublicList(id: UUID, public: Boolean)

    @Query("update collections set public_supplementary = :public, modified = now() where id = :id")
    suspend fun setPublicSupplementary(id: UUID, public: Boolean)

    @Query("update collections set ready = :ready, modified = now() where id = :id")
    suspend fun setReady(id: UUID, ready: OffsetDateTime)

    @Query("update collections set searchable = :searchable, modified = now() where id = :id")
    suspend fun setSearchable(id: UUID, searchable: Boolean)

    @Query("update collections set recommendable = :recommendable, modified = now() where id = :id")
    suspend fun setRecommendable(id: UUID, recommendable: Boolean)

    @Query("update collections set ready = null, modified = now() where id = :id")
    suspend fun setNotReady(id: UUID)

    @Query("update collections set template_metadata_id = :templateId, template_metadata_version = :templateVersion, modified = now() where id = :collectionId")
    suspend fun setTemplate(collectionId: UUID, templateId: UUID, templateVersion: Int)

    @Query("update collections set ordering = :ordering, modified = now() where id = :id")
    suspend fun setOrdering(id: UUID, ordering: JsonElement?)

    @Query("update collections set workflow_state_id = :state, workflow_state_pending_id = null, workflow_state_valid = null, modified = now() where id = :id")
    suspend fun setState(id: UUID, state: String)

    @Query("update collections set workflow_state_pending_id = :state, workflow_state_valid = :stateValid, modified = now() where id = :id")
    suspend fun setStatePending(id: UUID, state: String, stateValid: OffsetDateTime?)

    @Query("update collections set workflow_state_pending_id = null, workflow_state_valid = null, modified = now() where id = :id")
    suspend fun clearPendingState(id: UUID)

    @Query("delete from collections where id = :id")
    suspend fun deleteById(id: UUID)
}
