package bosca.communications.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.communications.model.BmlMessageProject
import bosca.communications.model.BmlMessageHostedProject
import bosca.communications.model.EmailPreview
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatus
import bosca.communications.model.DeliveryStatuses
import bosca.communications.model.RecipientDeliveryStatus
import bosca.communications.model.NotificationPreference
import bosca.communications.model.NotificationPreferenceMapping
import bosca.communications.model.NotificationSettings
import bosca.communications.model.NotificationType
import bosca.communications.service.BmlMessageRegistryService
import bosca.communications.service.BmlMessageTemplateRendererService
import bosca.communications.service.DeliveryTrackingService
import bosca.communications.service.NotificationPreferenceService
import bosca.communications.service.NotificationPreferenceMappingService
import bosca.communications.service.NotificationTypeService
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.graphql.Batch
import java.util.Locale

object CommunicationsQueries

@TypeController
class CommunicationsQueriesController(
    private val json: kotlinx.serialization.json.Json,
    private val bmlMessageRegistry: BmlMessageRegistryService,
    private val bmlMessageRenderer: BmlMessageTemplateRendererService,
    private val deliveryTracking: DeliveryTrackingService,
    private val notificationPreferences: NotificationPreferenceService,
    private val notificationPreferenceMappings: NotificationPreferenceMappingService,
    private val notificationTypes: NotificationTypeService,
    private val groupEvaluator: GroupEvaluator,
    private val profileService: ProfileService,
) : GraphQLController<CommunicationsQueries> {

    private suspend fun resolveProfileId(authentication: AuthenticationContext): UUID? {
        val principal = authentication.principal()?.asPrincipal() ?: return null
        return principal.primaryProfileId
            ?: profileService.getByPrincipal(principal.id).firstOrNull()?.id
    }

    @Field
    suspend fun deliveryStatus(authentication: AuthenticationContext, messageId: UUID, recipientId: UUID): DeliveryStatus? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return deliveryTracking.getStatus(messageId, recipientId)
    }

    @Field
    suspend fun messageStatuses(authentication: AuthenticationContext, messageId: UUID): List<DeliveryStatus> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return deliveryTracking.getStatusesForMessage(messageId)
    }

    @Field
    suspend fun deliveryStatuses(
        authentication: AuthenticationContext,
        offset: Long = 0,
        limit: Int = 50,
    ): DeliveryStatuses {
        groupEvaluator.verifyHasAdminGroup(authentication)
        require(offset >= 0) { "offset must not be negative" }
        require(limit in 1..100) { "limit must be between 1 and 100" }

        val statuses = deliveryTracking.getStatuses(offset, limit)
        val total = deliveryTracking.countStatuses()
        val recipientIds = statuses.map(DeliveryStatus::recipientId).distinct()
        val profiles = profileService.getAllByIds(recipientIds).associateBy { it.id }
        val attributes = Batch<UUID, List<ProfileAttribute>>(recipientIds)
        profileService.addAttributesToBatch(attributes)

        val recipientStatuses = statuses.map { status ->
            val profile = profiles[status.recipientId]
            val email = attributes.getData(status.recipientId)
                ?.getAttributeString("bosca.profiles.email", "email")
            RecipientDeliveryStatus(
                delivery = status,
                recipientName = profile?.name,
                recipientEmail = email,
            )
        }
        return DeliveryStatuses(recipientStatuses, total)
    }

    @Field
    suspend fun recipientHistory(authentication: AuthenticationContext, recipientId: UUID, offset: Long = 0, limit: Int = 50): List<DeliveryStatus> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return deliveryTracking.getHistoryForRecipient(recipientId, offset, limit)
    }

    @Field
    suspend fun recipientEvents(authentication: AuthenticationContext, recipientId: UUID, offset: Long = 0, limit: Int = 50): List<DeliveryEvent> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return deliveryTracking.getEventsForRecipient(recipientId, offset, limit)
    }

    @Field
    suspend fun notificationTypes(authentication: AuthenticationContext?): List<NotificationType> {
        val types = notificationTypes.list()
        return if (groupEvaluator.hasAdminGroup(authentication)) types else types.filterNot(NotificationType::hidden)
    }

    @Field
    suspend fun notificationPreferenceMappings(
        authentication: AuthenticationContext,
    ): List<NotificationPreferenceMapping> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return notificationPreferenceMappings.list()
    }

    @Field
    suspend fun myNotificationPreferences(authentication: AuthenticationContext): List<NotificationPreference> {
        val profileId = resolveProfileId(authentication) ?: return emptyList()
        return visibleNotificationPreferences(
            authentication,
            notificationPreferences.getPreferences(profileId),
        )
    }

    @Field
    suspend fun myNotificationSettings(authentication: AuthenticationContext): NotificationSettings? {
        val profileId = resolveProfileId(authentication) ?: return null
        return notificationPreferences.getSettings(profileId)
    }

    @Field
    suspend fun notificationPreferences(authentication: AuthenticationContext, profileId: UUID): List<NotificationPreference> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return notificationPreferences.getPreferences(profileId)
    }

    @Field
    suspend fun notificationSettings(authentication: AuthenticationContext, profileId: UUID): NotificationSettings? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return notificationPreferences.getSettings(profileId)
    }

    @Field
    suspend fun tokenNotificationPreferences(authentication: AuthenticationContext?, token: String): List<NotificationPreference>? {
        val profileId = notificationPreferences.profileIdForToken(token) ?: return null
        // All channels: the token-reached preferences page manages email AND push. Consumers
        // that only care about email (Studio's original page) match rows by (type, channel).
        return visibleNotificationPreferences(
            authentication,
            notificationPreferences.getPreferences(profileId),
        )
    }

    private suspend fun visibleNotificationPreferences(
        authentication: AuthenticationContext?,
        preferences: List<NotificationPreference>,
    ): List<NotificationPreference> {
        if (groupEvaluator.hasAdminGroup(authentication)) return preferences
        val hiddenTypes = notificationTypes.list()
            .asSequence()
            .filter(NotificationType::hidden)
            .map(NotificationType::key)
            .toSet()
        return preferences.filterNot { it.type in hiddenTypes }
    }

    @Field
    suspend fun bmlMessageProjects(authentication: AuthenticationContext): List<BmlMessageProject> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return bmlMessageRegistry.listProjects()
    }

    @Field
    suspend fun bmlMessageProject(authentication: AuthenticationContext, projectKey: String): BmlMessageProject? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return bmlMessageRegistry.getProject(projectKey)
    }

    @Field
    suspend fun bmlMessageHostedProjects(authentication: AuthenticationContext): List<BmlMessageHostedProject> {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return bmlMessageRenderer.hostedProjects()
    }

    @Field
    suspend fun bmlMessageProjectVersions(authentication: AuthenticationContext, project: String): List<String> {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return bmlMessageRenderer.versions(project)
    }

    @Field
    suspend fun emailPreview(
        authentication: AuthenticationContext,
        project: String,
        templateKey: String,
        payload: String?,
        version: String?,
        recipientName: String?,
        recipientEmail: String?,
        locale: String?,
    ): EmailPreview {
        groupEvaluator.verifyHasEditorGroup(authentication)
        val parsed = payload?.takeIf { it.isNotBlank() }?.let {
            try {
                json.parseToJsonElement(it)
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid payload JSON: ${e.message}")
            }
        }
        return bmlMessageRenderer.preview(
            MessageBmlTemplate(project, templateKey, parsed),
            version = version,
            recipientName = recipientName,
            recipientEmail = recipientEmail,
            locale = locale?.let(Locale::forLanguageTag),
        )
    }
}
