package bosca.content.metadata.repository

import bosca.content.metadata.model.MetadataProfile
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID


@Repository
interface MetadataProfileRepository {

    @Query("insert into metadata_profiles (metadata_id, profile_id, relationship, sort) values (:metadataId, :profileId, :relationship, :sort)")
    suspend fun add(profile: MetadataProfile)

    @Query("select * from metadata_profiles where metadata_id = :id")
    suspend fun getByMetadataId(id: UUID): List<MetadataProfile>
}