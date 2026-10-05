package bosca.profile.profile.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.attribute.service.ProfileAttributeService
import bosca.profile.attribute.verification.AttributeVerificationService
import bosca.profile.bookmark.service.ProfileBookmarkService
import bosca.profile.guide.model.ProfileGuideProgress
import bosca.profile.guide.service.ProfileGuideService
import bosca.profile.mark.service.ProfileMarkService
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.rating.service.ProfileRatingService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.serialization.json.JsonElement

object ProfileMutation

@TypeController
class ProfileMutationController(
    private val bookmarkService: ProfileBookmarkService,
    private val markService: ProfileMarkService,
    private val guideService: ProfileGuideService,
    private val ratingService: ProfileRatingService,
    private val permissionEvaluator: ProfilePermissionEvaluator,
    private val attributeService: ProfileAttributeService,
    private val attributeVerificationService: AttributeVerificationService,
    private val profileService: ProfileService
) : GraphQLController<ProfileMutation> {

    private suspend fun getProfile(authentication: AuthenticationContext, profileId: UUID?): Profile {
        if (profileId != null) {
            val profile = profileService.getById(profileId)
            if (!permissionEvaluator.isAllowed(authentication, profile, PermissionAction.EDIT)) {
                throw SecurityException("user is not allowed to edit profile")
            }
            return profile
        }
        val principal = authentication.principal() ?: throw SecurityException("user is not authenticated")
        return profileService.getPrimaryProfile(principal.asPrincipal()) ?: error("user has no primary profile")
    }

    @Field
    fun thirdparty() = ThirdPartyExtensionMutation()

    @Field
    suspend fun addMark(
        authentication: AuthenticationContext,
        metadataId: UUID? = null,
        version: Int? = null,
        collectionId: UUID? = null,
        attributes: JsonElement? = null,
        profileId: UUID? = null
    ): Boolean {
        val profile = getProfile(authentication, profileId)
        permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT)
        when {
            metadataId != null -> {
                markService.addMark(
                    profileId = profile.id,
                    metadataId = metadataId,
                    metadataVersion = version,
                    attributes = attributes
                )
            }

            collectionId != null -> {
                markService.addMark(
                    profileId = profile.id,
                    collectionId = collectionId,
                    attributes = attributes
                )
            }
        }
        return true
    }

    @Field
    suspend fun addProgress(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int,
        stepId: Long,
        attributes: JsonElement,
        profileId: UUID? = null,
    ): ProfileGuideProgress? {
        val profile = getProfile(authentication, profileId)
        permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT)
        return guideService.addProgress(
            profileId = profile.id,
            metadataId = metadataId,
            metadataVersion = metadataVersion,
            stepId = stepId,
            attributes = attributes
        )
    }

    @Field
    suspend fun deleteProgress(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int,
        profileId: UUID? = null
    ): Boolean {
        val profile = getProfile(authentication, profileId)
        permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT)
        guideService.deleteProgress(
            profileId = profile.id,
            metadataId = metadataId,
            metadataVersion = metadataVersion
        )
        return true
    }

    @Field
    suspend fun addBookmark(
        authentication: AuthenticationContext,
        metadataId: UUID? = null,
        version: Int? = null,
        collectionId: UUID? = null,
        attributes: JsonElement? = null,
        profileId: UUID? = null
    ): Boolean {
        val profile = getProfile(authentication, profileId)
        permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT)
        when {
            metadataId != null -> {
                bookmarkService.addBookmark(
                    profileId = profile.id,
                    metadataId = metadataId,
                    metadataVersion = version,
                    attributes = attributes
                )
            }

            collectionId != null -> {
                bookmarkService.addBookmark(
                    profileId = profile.id,
                    collectionId = collectionId,
                    attributes = attributes
                )
            }
        }
        return true
    }

    @Field
    suspend fun deleteBookmark(
        authentication: AuthenticationContext,
        metadataId: UUID? = null,
        version: Int? = null,
        collectionId: UUID? = null,
        profileId: UUID? = null
    ): Boolean {
        val profile = getProfile(authentication, profileId)
        permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT)
        when {
            metadataId != null -> {
                bookmarkService.deleteBookmark(
                    profileId = profile.id,
                    metadataId = metadataId,
                    metadataVersion = version
                )
            }

            collectionId != null -> {
                bookmarkService.deleteBookmark(
                    profileId = profile.id,
                    collectionId = collectionId
                )
            }
        }
        return true
    }

    @Field
    suspend fun deleteMark(
        authentication: AuthenticationContext,
        id: Long,
        profileId: UUID? = null
    ): Boolean {
        val profile = getProfile(authentication, profileId)
        permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT)
        markService.deleteMark(id, profile.id)
        return true
    }

    @Field
    suspend fun addRating(
        authentication: AuthenticationContext,
        rating: Int,
        collectionId: UUID? = null,
        metadataId: UUID? = null,
        version: Int? = null,
        profileId: UUID? = null
    ): Boolean {
        val profile = getProfile(authentication, profileId)
        permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT)
        when {
            metadataId != null -> {
                ratingService.addRating(
                    profileId = profile.id,
                    metadataId = metadataId,
                    metadataVersion = version,
                    rating = rating
                )
            }

            collectionId != null -> {
                ratingService.addRating(
                    profileId = profile.id,
                    collectionId = collectionId,
                    rating = rating
                )
            }
        }
        return true
    }

    @Field
    suspend fun deleteAttribute(
        authentication: AuthenticationContext,
        attributeId: UUID,
        profileId: UUID? = null
    ): Boolean {
        val profile = getProfile(authentication, profileId)
        permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT)
        attributeService.deleteAttribute(profile.id, attributeId)
        return true
    }

    /**
     * Begins (or re-sends) verification of a profile attribute through the generic verification framework:
     * mints a one-time token, records it on the [typeId] attribute, and delivers that type's challenge to the
     * value being proven (an email link today, an SMS code tomorrow). Type-agnostic — the channel is selected
     * from the DI registry by [typeId]. A no-op when the attribute is missing, valueless, or already verified.
     * Returns true once the request has been accepted (delivery is asynchronous).
     */
    @Field
    suspend fun requestAttributeVerification(
        authentication: AuthenticationContext,
        typeId: String,
        profileId: UUID? = null,
        call: ServerCall
    ): Boolean {
        val profile = getProfile(authentication, profileId)
        permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT)
        // Carry the host the user is on so the (re-sent) verification email routes back to it (multi-host).
        // appOrigin reads the browser Origin/Referer rather than the addressed API host, so a proxied call
        // (Studio host -> API host) still names the Studio host. The service validates it against the
        // allow-list and falls back to the default app origin otherwise.
        attributeVerificationService.requestVerification(profile.id, typeId, call.request.appOrigin)
        return true
    }
}
