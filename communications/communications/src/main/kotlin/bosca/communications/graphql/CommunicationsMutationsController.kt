package bosca.communications.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.communications.model.BmlMessageProject
import bosca.communications.model.DeliveryChannel
import bosca.communications.model.NotificationPreference
import bosca.communications.model.NotificationPreferenceMapping
import bosca.communications.model.NotificationSettings
import bosca.communications.model.NotificationType
import bosca.communications.service.BmlMessageRegistryService
import bosca.communications.service.NotificationPreferenceService
import bosca.communications.service.NotificationPreferenceMappingService
import bosca.communications.service.NotificationTypeService
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object CommunicationsMutations

@TypeController
class CommunicationsMutationsController(
    private val bmlMessageRegistry: BmlMessageRegistryService,
    private val notificationPreferences: NotificationPreferenceService,
    private val notificationPreferenceMappings: NotificationPreferenceMappingService,
    private val notificationTypes: NotificationTypeService,
    private val groupEvaluator: GroupEvaluator,
    private val profileService: ProfileService,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
) : GraphQLController<CommunicationsMutations> {

    private suspend fun resolveProfileId(authentication: AuthenticationContext): UUID? {
        val principal = authentication.principal()?.asPrincipal() ?: return null
        return principal.primaryProfileId
            ?: profileService.getByPrincipal(principal.id).firstOrNull()?.id
    }

    @Field
    suspend fun unsubscribe(authentication: AuthenticationContext?, token: String): Boolean =
        notificationPreferences.unsubscribeByToken(token)

    @Field
    suspend fun updateMyNotificationPreference(
        authentication: AuthenticationContext,
        channel: DeliveryChannel,
        type: String,
        optedOut: Boolean,
    ): NotificationPreference {
        val profileId = resolveProfileId(authentication)
            ?: throw SecurityException("No profile for the current principal")
        verifyNotificationTypeVisible(authentication, type)
        return notificationPreferences.setOptOut(profileId, channel, type, optedOut)
    }

    @Field
    suspend fun updateMyQuietHours(
        authentication: AuthenticationContext,
        timeZone: String?,
        dndStartLocal: String?,
        dndEndLocal: String?,
    ): NotificationSettings {
        val profileId = resolveProfileId(authentication)
            ?: throw SecurityException("No profile for the current principal")
        return notificationPreferences.setQuietHours(profileId, timeZone, dndStartLocal, dndEndLocal)
    }

    @Field
    suspend fun setNotificationPreference(
        authentication: AuthenticationContext,
        profileId: UUID,
        channel: DeliveryChannel,
        type: String,
        optedOut: Boolean,
    ): NotificationPreference {
        val profile = profileService.getById(profileId)
        profilePermissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT)
        verifyNotificationTypeVisible(authentication, type)
        return notificationPreferences.setOptOut(profileId, channel, type, optedOut)
    }

    @Field
    suspend fun updateTokenNotificationPreference(
        authentication: AuthenticationContext?,
        token: String,
        type: String,
        optedOut: Boolean,
        channel: DeliveryChannel?,
    ): Boolean {
        val profileId = notificationPreferences.profileIdForToken(token) ?: return false
        verifyNotificationTypeVisible(authentication, type)
        // No channel = EMAIL, the original email-only contract (Studio's token page passes none).
        notificationPreferences.setOptOut(profileId, channel ?: DeliveryChannel.EMAIL, type, optedOut)
        return true
    }

    /**
     * Keeps hidden catalog entries out of user-facing mutation paths as well as queries.
     * Administrators retain access for support and configuration workflows.
     */
    private suspend fun verifyNotificationTypeVisible(
        authentication: AuthenticationContext?,
        type: String,
    ) {
        if (!groupEvaluator.hasAdminGroup(authentication) && notificationTypes.get(type)?.hidden == true) {
            throw IllegalArgumentException("unknown notification type: $type")
        }
    }

    @Field
    suspend fun setNotificationSettings(
        authentication: AuthenticationContext,
        profileId: UUID,
        timeZone: String?,
        dndStartLocal: String?,
        dndEndLocal: String?,
    ): NotificationSettings {
        val profile = profileService.getById(profileId)
        profilePermissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT)
        return notificationPreferences.setQuietHours(profileId, timeZone, dndStartLocal, dndEndLocal)
    }

    @Field
    suspend fun setNotificationType(
        authentication: AuthenticationContext,
        key: String,
        name: String,
        description: String?,
        optional: Boolean,
        defaultEmailEnabled: Boolean,
        defaultPushEnabled: Boolean,
        displayOrder: Int,
        hidden: Boolean,
    ): NotificationType {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return notificationTypes.set(
            key,
            name,
            description,
            optional,
            defaultEmailEnabled,
            defaultPushEnabled,
            displayOrder,
            hidden,
        )
    }

    @Field
    suspend fun deleteNotificationType(authentication: AuthenticationContext, key: String): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return notificationTypes.delete(key)
    }

    @Field
    suspend fun setNotificationPreferenceMapping(
        authentication: AuthenticationContext,
        type: String,
        channel: DeliveryChannel,
        provider: String,
        externalId: String,
    ): NotificationPreferenceMapping {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return notificationPreferenceMappings.set(type, channel, provider, externalId)
    }

    @Field
    suspend fun deleteNotificationPreferenceMapping(
        authentication: AuthenticationContext,
        type: String,
        channel: DeliveryChannel,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return notificationPreferenceMappings.delete(type, channel)
    }

    @Field
    suspend fun registerBmlMessageProject(
        authentication: AuthenticationContext,
        projectKey: String,
        description: String?,
        repositoryId: UUID?,
    ): BmlMessageProject {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return bmlMessageRegistry.registerProject(projectKey, description, repositoryId)
    }

    @Field
    suspend fun pinBmlMessageProjectVersion(
        authentication: AuthenticationContext,
        projectKey: String,
        version: String?,
    ): BmlMessageProject {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return bmlMessageRegistry.pinProjectVersion(projectKey, version)
    }

    @Field
    suspend fun removeBmlMessageProject(authentication: AuthenticationContext, projectKey: String): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return bmlMessageRegistry.removeProject(projectKey)
    }

}
