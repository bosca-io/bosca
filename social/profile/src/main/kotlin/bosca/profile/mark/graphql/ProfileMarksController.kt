package bosca.profile.mark.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.mark.model.ProfileMark
import bosca.profile.mark.service.ProfileMarkService
import bosca.profile.model.Profile
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

class ProfileMarks(val profile: Profile)

@TypeController
class ProfileMarksController(
    private val service: ProfileMarkService,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator
) : GraphQLController<ProfileMarks> {

    @Field
    suspend fun all(
        authentication: AuthenticationContext,
        marks: ProfileMarks,
        offset: Long? = null,
        limit: Int? = null
    ): List<ProfileMark> {
        val profile = marks.profile
        if (!profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW)) {
            return emptyList()
        }
        val actualOffset = offset ?: 0L
        val actualLimit = limit ?: 25
        return service.getMarks(profile.id, actualLimit, actualOffset.toInt())
    }

    @Field
    suspend fun count(
        authentication: AuthenticationContext,
        marks: ProfileMarks,
    ): Long {
        val profile = marks.profile
        if (!profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW)) {
            return 0L
        }
        return service.getMarkCount(profile.id)
    }

    @Field
    suspend fun mark(
        authentication: AuthenticationContext,
        marks: ProfileMarks,
        metadataId: UUID? = null,
        metadataVersion: Int? = null,
        collectionId: UUID? = null,
        offset: Long,
        limit: Int
    ): List<ProfileMark> {
        val profile = marks.profile
        if (!profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW)) {
            return emptyList()
        }
        return when {
            metadataId != null && metadataVersion != null -> {
                service.getMarks(
                    profile.id,
                    metadataId,
                    metadataVersion,
                    limit,
                    offset
                )
            }
            collectionId != null -> {
                service.getMarks(profile.id, collectionId, limit, offset)
            }
            else -> emptyList()
        }
    }

    @Field
    suspend fun markCount(
        authentication: AuthenticationContext,
        marks: ProfileMarks,
        metadataId: UUID? = null,
        metadataVersion: Int? = null,
        collectionId: UUID? = null
    ): Long {
        val profile = marks.profile
        if (!profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW)) {
            return 0L
        }
        // TODO: Update service to support collection-based counting
        return when {
            metadataId != null && metadataVersion != null -> {
                service.getMarkCount(profile.id, metadataId, metadataVersion)
            }
            collectionId != null -> {
                service.getMarkCount(profile.id, collectionId)
            }
            else -> 0L
        }
    }
}
