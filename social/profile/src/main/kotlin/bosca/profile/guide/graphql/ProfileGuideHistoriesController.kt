package bosca.profile.guide.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.guide.model.ProfileGuideHistory
import bosca.profile.guide.service.ProfileGuideService
import bosca.profile.model.Profile
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.serialization.Serializable


@Serializable
class ProfileGuideHistories(val profile: Profile)

@TypeController
class ProfileGuideHistoriesController(
    private val service: ProfileGuideService
) : GraphQLController<ProfileGuideHistories> {

    private fun validateProfileOwnership(authentication: AuthenticationContext, profile: Profile): Boolean {
        val principal = authentication.principal()
        return profile.principal != null && profile.principal == principal?.id
    }

    @Field
    suspend fun all(
        authentication: AuthenticationContext,
        histories: ProfileGuideHistories,
        offset: Long,
        limit: Int
    ): List<ProfileGuideHistory> {
        val profile = histories.profile
        if (!validateProfileOwnership(authentication, profile)) {
            return emptyList()
        }
        return service.getAllHistory(profile.id, limit, offset)
    }

    @Field
    suspend fun count(
        authentication: AuthenticationContext,
        histories: ProfileGuideHistories
    ): Long {
        val profile = histories.profile
        if (!validateProfileOwnership(authentication, profile)) {
            return 0L
        }
        return service.getHistoryCount(profile.id)
    }

    @Field
    suspend fun history(
        authentication: AuthenticationContext,
        histories: ProfileGuideHistories,
        id: UUID,
        version: Int,
        offset: Long,
        limit: Int
    ): List<ProfileGuideHistory> {
        val profile = histories.profile
        if (!validateProfileOwnership(authentication, profile)) {
            return emptyList()
        }
        return service.getHistory(
            profile.id,
            id,
            version,
            limit,
            offset
        )
    }

    @Field
    suspend fun historyCount(
        authentication: AuthenticationContext,
        histories: ProfileGuideHistories,
        id: UUID,
        version: Int
    ): Long {
        val profile = histories.profile
        if (!validateProfileOwnership(authentication, profile)) {
            return 0L
        }
        return service.getHistoryCount(profile.id, id, version)
    }
}