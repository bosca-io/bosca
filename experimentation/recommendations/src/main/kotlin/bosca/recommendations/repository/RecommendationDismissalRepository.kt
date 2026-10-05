package bosca.recommendations.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.recommendations.model.RecommendationDismissal
import bosca.serialization.UUID

/**
 * Manages user-initiated dismissals of recommended content. When a profile dismisses a
 * metadata item or collection, the dismissal is recorded here so that future recommendation
 * queries can exclude those items. Supports both metadata-level and collection-level
 * dismissals with idempotent insert-on-conflict semantics.
 */
@Repository
interface RecommendationDismissalRepository {

    @Query("""
        select metadata_id from recommendations.dismissals
        where profile_id = :profileId and metadata_id is not null
    """)
    suspend fun getDismissedMetadataIds(profileId: UUID): List<UUID>

    @Query("""
        select collection_id from recommendations.dismissals
        where profile_id = :profileId and collection_id is not null
    """)
    suspend fun getDismissedCollectionIds(profileId: UUID): List<UUID>

    @Query("""
        insert into recommendations.dismissals (profile_id, metadata_id, collection_id)
        values (:profileId, :metadataId, :collectionId)
        on conflict do nothing
        returning *
    """)
    suspend fun add(dismissal: RecommendationDismissal): RecommendationDismissal?

    @Query("""
        delete from recommendations.dismissals
        where profile_id = :profileId and metadata_id = :metadataId and metadata_id is not null
    """)
    suspend fun removeByMetadata(profileId: UUID, metadataId: UUID)

    @Query("""
        delete from recommendations.dismissals
        where profile_id = :profileId and collection_id = :collectionId and collection_id is not null
    """)
    suspend fun removeByCollection(profileId: UUID, collectionId: UUID)
}
