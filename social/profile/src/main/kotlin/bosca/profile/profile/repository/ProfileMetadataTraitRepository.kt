package bosca.profile.profile.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.profile.model.ProfileMetadataTrait
import bosca.serialization.UUID

@Repository
interface ProfileMetadataTraitRepository {

    @Query("select * from profile_metadata_trait where id = :profileId")
    suspend fun getProfileMetadataTraitsByProfileId(profileId: UUID): List<ProfileMetadataTrait>

    @Query("select * from profile_metadata_trait where trait_id = :traitId")
    suspend fun getProfileMetadataTraitsByTraitId(traitId: String): List<ProfileMetadataTrait>

    @Query("select * from profile_metadata_trait where profile_id = :profileId and trait_id = :traitId")
    suspend fun getProfileMetadataTraitsByProfileIdAndTraitId(profileId: UUID, traitId: String): ProfileMetadataTrait?
}
