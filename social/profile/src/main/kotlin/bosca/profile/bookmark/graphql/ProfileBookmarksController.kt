package bosca.profile.bookmark.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.bookmark.model.ProfileBookmark
import bosca.profile.bookmark.service.ProfileBookmarkService
import bosca.profile.model.Profile
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

class ProfileBookmarks(val profile: Profile)

@TypeController
class ProfileBookmarksController(private val service: ProfileBookmarkService) : GraphQLController<ProfileBookmarks> {

    private fun validateProfileOwnership(authentication: AuthenticationContext, profile: Profile): Boolean {
        val principal = authentication.principal()
        return profile.principal != null && profile.principal == principal?.id
    }

    @Field
    suspend fun bookmarks(
        authentication: AuthenticationContext,
        bookmarks: ProfileBookmarks,
        offset: Long? = null,
        limit: Long? = null
    ): List<ProfileBookmark> {
        if (!validateProfileOwnership(authentication, bookmarks.profile)) {
            return emptyList()
        }
        val actualOffset = offset ?: 0L
        val actualLimit = limit ?: 25L
        return service.getBookmarks(
            bookmarks.profile.id,
            actualLimit.toInt(),
            actualOffset.toInt()
        )
    }

    @Field
    suspend fun count(
        authentication: AuthenticationContext,
        bookmarks: ProfileBookmarks
    ): Long {
        if (!validateProfileOwnership(authentication, bookmarks.profile)) {
            return 0L
        }
        return service.getBookmarkCount(bookmarks.profile.id)
    }

    @Field
    suspend fun bookmark(
        authentication: AuthenticationContext,
        bookmarks: ProfileBookmarks,
        metadataId: UUID? = null,
        metadataVersion: Int? = null,
        collectionId: UUID? = null
    ): ProfileBookmark? {
        if (!validateProfileOwnership(authentication, bookmarks.profile)) {
            return null
        }
        return when {
            metadataId != null && metadataVersion != null -> {
                service.getBookmark(bookmarks.profile.id, metadataId, metadataVersion)
            }

            collectionId != null -> {
                service.getBookmark(bookmarks.profile.id, collectionId)
            }

            else -> null
        }
    }
}