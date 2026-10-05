package bosca.profile.rating.service

import bosca.profile.rating.events.ProfileRatingAdded
import bosca.profile.rating.events.ProfileRatingUpdated
import bosca.profile.rating.events.dispatch
import bosca.profile.rating.model.ProfileRating
import bosca.profile.rating.repository.ProfileRatingRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class ProfileRatingServiceImpl(
    private val repository: ProfileRatingRepository
) : ProfileRatingService {

    override suspend fun getRatingsByProfile(profileId: UUID): List<ProfileRating> {
        return repository.findByProfileId(profileId)
    }

    override suspend fun getRating(
        profileId: UUID,
        metadataId: UUID,
        metadataVersion: Int
    ): ProfileRating? {
        return repository.findByProfileAndMetadata(profileId, metadataId, metadataVersion)
    }

    override suspend fun getRating(profileId: UUID, collectionId: UUID): ProfileRating? {
        return repository.findByProfileAndCollection(profileId, collectionId)
    }

    override suspend fun getAverageRating(metadataId: UUID, metadataVersion: Int): Double? {
        return repository.getAverageRating(metadataId, metadataVersion)
    }

    override suspend fun getAverageRating(collectionId: UUID): Double? {
        return repository.getAverageRating(collectionId)
    }

    override suspend fun addRating(
        profileId: UUID,
        rating: Int,
        metadataId: UUID?,
        metadataVersion: Int?,
        collectionId: UUID?
    ): ProfileRating {
        val profileRating = ProfileRating(
            profileId = profileId,
            rating = rating,
            metadataId = metadataId,
            metadataVersion = metadataVersion,
            collectionId = collectionId
        )
        val saved = repository.add(profileRating)
        ProfileRatingAdded(profileId, rating, metadataId, metadataVersion, collectionId).dispatch()
        return saved
    }

    override suspend fun updateRating(
        profileId: UUID,
        rating: Int,
        metadataId: UUID?,
        metadataVersion: Int?,
        collectionId: UUID?
    ): ProfileRating? {
        val existing = when {
            metadataId != null && metadataVersion != null -> {
                repository.findByProfileAndMetadata(profileId, metadataId, metadataVersion)
            }

            collectionId != null -> {
                repository.findByProfileAndCollection(profileId, collectionId)
            }

            else -> null
        }

        return if (existing != null) {
            val updated = repository.update(existing.copy(rating = rating))
            ProfileRatingUpdated(profileId, rating, existing.rating, metadataId, metadataVersion, collectionId).dispatch()
            updated
        } else {
            addRating(profileId, rating, metadataId, metadataVersion, collectionId)
        }
    }
}
