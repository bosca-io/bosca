package bosca.content.collection.repository

import bosca.content.collection.model.CollectionLanguageVariant
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@Repository
interface CollectionLanguageVariantRepository {

    @Query("select * from collection_language_variants where id = :id")
    suspend fun getLanguageVariants(id: UUID): List<CollectionLanguageVariant>

    @Query("select * from collection_language_variants where id = any(:ids)")
    suspend fun getLanguageVariants(ids: List<UUID>): List<CollectionLanguageVariant>

    @Query("select * from collection_language_variants where id = any(:ids) and lower(language_tag) = lower(:languageTag)")
    suspend fun getLanguageVariants(ids: CollectionVariantBatchId): List<CollectionLanguageVariant>

    @Query("select * from collection_language_variants where id = :id and lower(language_tag) = lower(:languageTag)")
    suspend fun getLanguageVariant(id: UUID, languageTag: String): CollectionLanguageVariant?

    @Query("select * from collection_language_variants where id = :id and lower(language_tag) = lower(:languageTag) for update")
    suspend fun getLanguageVariantForUpdate(id: UUID, languageTag: String): CollectionLanguageVariant?

    @Query("insert into collection_language_variants (id, language_tag, name, description, attributes, workflow_state_id, public, public_list, public_supplementary, searchable, recommendable) values (:id, :languageTag, :name, :description, :attributes, :workflowStateId, :public, :publicList, :publicSupplementary, :searchable, :recommendable) on conflict (id, language_tag) do update set name = :name, description = :description, attributes = :attributes, public = :public, public_list = :publicList, public_supplementary = :publicSupplementary, searchable = :searchable, recommendable = :recommendable returning *")
    suspend fun add(variant: CollectionLanguageVariant): CollectionLanguageVariant

    @Query("update collection_language_variants set name = :name, description = :description, attributes = :attributes, public = :public, public_list = :publicList, public_supplementary = :publicSupplementary, searchable = :searchable, recommendable = :recommendable where id = :id and language_tag = :languageTag returning *")
    suspend fun edit(variant: CollectionLanguageVariant): CollectionLanguageVariant

    @Query("delete from collection_language_variants where id = :id and language_tag = :languageTag")
    suspend fun delete(id: CollectionVariantId)

    @Query("update collection_language_variants set workflow_state_id = :state, workflow_state_pending_id = null, workflow_state_valid = null where id = :id and language_tag = :languageTag")
    suspend fun setState(id: UUID, languageTag: String, state: String)

    @Query("update collection_language_variants set workflow_state_pending_id = :state, workflow_state_valid = :stateValid where id = :id and language_tag = :languageTag")
    suspend fun setStatePending(id: UUID, languageTag: String, state: String, stateValid: OffsetDateTime?)

    @Query("update collection_language_variants set workflow_state_pending_id = null, workflow_state_valid = null where id = :id and language_tag = :languageTag")
    suspend fun clearPendingState(id: UUID, languageTag: String)

    @Query("update collection_language_variants set ready = :ready where id = :id and language_tag = :languageTag")
    suspend fun setReady(id: UUID, languageTag: String, ready: OffsetDateTime)

    @Query("update collection_language_variants set ready = null where id = :id and language_tag = :languageTag")
    suspend fun setNotReady(id: UUID, languageTag: String)

    @Query("update collection_language_variants set public = :public where id = :id and language_tag = :languageTag")
    suspend fun setPublic(id: UUID, languageTag: String, public: Boolean)

    @Query("update collection_language_variants set public_list = :public where id = :id and language_tag = :languageTag")
    suspend fun setPublicList(id: UUID, languageTag: String, public: Boolean)

    @Query("update collection_language_variants set public_supplementary = :public where id = :id and language_tag = :languageTag")
    suspend fun setPublicSupplementary(id: UUID, languageTag: String, public: Boolean)

    @Query("update collection_language_variants set searchable = :searchable where id = :id and language_tag = :languageTag")
    suspend fun setSearchable(id: UUID, languageTag: String, searchable: Boolean)

    @Query("update collection_language_variants set recommendable = :recommendable where id = :id and language_tag = :languageTag")
    suspend fun setRecommendable(id: UUID, languageTag: String, recommendable: Boolean)
}

data class CollectionVariantBatchId(
    val ids: List<UUID>,
    val languageTag: String
)

data class CollectionVariantId(val id: UUID, val languageTag: String)
