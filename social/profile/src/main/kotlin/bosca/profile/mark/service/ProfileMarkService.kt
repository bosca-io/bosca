package bosca.profile.mark.service

import bosca.profile.mark.events.ProfileMarkAdded
import bosca.profile.mark.events.ProfileMarkDeleted
import bosca.profile.mark.events.dispatch
import bosca.profile.mark.model.ProfileMark
import bosca.profile.mark.model.ProfileMetadataIdsQuery
import bosca.profile.mark.repository.ProfileMarkRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonElement

@ServiceImplementation
class ProfileMarkServiceImpl(
    private val repository: ProfileMarkRepository
) : ProfileMarkService {

    override suspend fun getMarks(profileId: UUID, limit: Int, offset: Int): List<ProfileMark> {
        return repository.findByProfileId(profileId, limit, offset)
    }

    override suspend fun getMarks(
        profileId: UUID,
        metadataId: UUID,
        metadataVersion: Int,
        limit: Int,
        offset: Long
    ): List<ProfileMark> {
        return repository.findByProfileAndMetadata(profileId, metadataId, metadataVersion, limit, offset)
    }

    override suspend fun getMarks(profileId: UUID, metadataIds: List<UUID>): List<ProfileMark> {
        if (metadataIds.isEmpty()) return emptyList()
        return repository.findByProfileAndMetadataIds(ProfileMetadataIdsQuery(profileId, metadataIds))
    }

    override suspend fun getMarks(profileId: UUID, collectionId: UUID, limit: Int, offset: Long): List<ProfileMark> {
        return repository.findByProfileAndCollection(profileId, collectionId, limit, offset)
    }

    override suspend fun getMarkCount(profileId: UUID, metadataId: UUID, metadataVersion: Int): Long {
        return repository.countByProfileAndMetadata(profileId, metadataId, metadataVersion)
    }

    override suspend fun getMarkCount(profileId: UUID, collectionId: UUID): Long {
        return repository.countByProfileAndCollection(profileId, collectionId)
    }

    override suspend fun getMarkCount(profileId: UUID): Long {
        return repository.countByProfileId(profileId)
    }

    override suspend fun addMark(
        profileId: UUID,
        metadataId: UUID?,
        metadataVersion: Int?,
        collectionId: UUID?,
        attributes: JsonElement?
    ) {
        val mark = ProfileMark(
            profileId = profileId,
            metadataId = metadataId,
            metadataVersion = metadataVersion,
            collectionId = collectionId,
            attributes = attributes
        )
        val saved = repository.add(mark)
        ProfileMarkAdded(saved.profileId, saved.id, saved.metadataId, saved.metadataVersion, saved.collectionId).dispatch()
    }

    override suspend fun deleteMark(id: Long, profileId: UUID) {
        val deleted = repository.deleteById(id, profileId) ?: return
        ProfileMarkDeleted(deleted.profileId, deleted.id, deleted.metadataId, deleted.metadataVersion, deleted.collectionId).dispatch()
    }
}
