package bosca.profile.bookmark.service

import bosca.profile.bookmark.events.ProfileBookmarkAdded
import bosca.profile.bookmark.events.ProfileBookmarkDeleted
import bosca.profile.bookmark.events.dispatch
import bosca.profile.bookmark.model.ProfileBookmark
import bosca.profile.bookmark.repository.ProfileBookmarkRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonElement

@ServiceImplementation
class ProfileBookmarkServiceImpl(
    private val repository: ProfileBookmarkRepository
) : ProfileBookmarkService {

    override suspend fun getBookmarks(profileId: UUID, limit: Int, offset: Int): List<ProfileBookmark> {
        return repository.findByProfileId(profileId, limit, offset)
    }

    override suspend fun getBookmarkCount(profileId: UUID): Long {
        return repository.countByProfileId(profileId)
    }

    override suspend fun getBookmark(profileId: UUID, metadataId: UUID, metadataVersion: Int): ProfileBookmark? {
        return repository.findByProfileAndMetadata(profileId, metadataId, metadataVersion)
    }

    override suspend fun getBookmark(profileId: UUID, collectionId: UUID): ProfileBookmark? {
        return repository.findByProfileAndCollection(profileId, collectionId)
    }

    override suspend fun addBookmark(
        profileId: UUID,
        metadataId: UUID?,
        metadataVersion: Int?,
        collectionId: UUID?,
        attributes: JsonElement?
    ) {
        val bookmark = ProfileBookmark(
            profileId = profileId,
            metadataId = metadataId,
            metadataVersion = metadataVersion,
            collectionId = collectionId,
            attributes = attributes
        )
        repository.add(bookmark)
        ProfileBookmarkAdded(profileId, metadataId, metadataVersion, collectionId).dispatch()
    }

    override suspend fun deleteBookmark(
        profileId: UUID,
        metadataId: UUID?,
        metadataVersion: Int?,
        collectionId: UUID?
    ) {
        when {
            metadataId != null && metadataVersion != null -> {
                val bookmark = repository.findByProfileAndMetadata(profileId, metadataId, metadataVersion)
                if (bookmark != null) {
                    repository.deleteById(bookmark.id)
                    ProfileBookmarkDeleted(profileId, metadataId, metadataVersion, null).dispatch()
                }
            }

            collectionId != null -> {
                val bookmark = repository.findByProfileAndCollection(profileId, collectionId)
                if (bookmark != null) {
                    repository.deleteById(bookmark.id)
                    ProfileBookmarkDeleted(profileId, null, null, collectionId).dispatch()
                }
            }
        }
    }
}
