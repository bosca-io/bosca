package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.workops.model.notification.Notification
import bosca.workops.model.notification.NotificationChannel
import bosca.workops.model.notification.NotificationPreference
import bosca.workops.model.notification.NotificationScheme
import bosca.workops.model.notification.NotificationSubscription
import bosca.workops.model.notification.TaskWatcher
import bosca.workops.service.NotificationInboxService
import bosca.workops.service.NotificationPreferenceInput
import bosca.workops.service.NotificationPreferenceService
import bosca.workops.service.NotificationSchemeService
import bosca.workops.service.NotificationSubscriptionService
import bosca.workops.service.SavedFilterService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import bosca.workops.service.TaskWatcherService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * GraphQL update input mirror — the wire shape of `eventChannels`
 * is `{ "<EVENT_NAME>": [ "IN_APP", "EMAIL", ... ], ... }`.
 */
@kotlinx.serialization.Serializable
data class NotificationPreferenceUpdateInput(
    @kotlinx.serialization.Contextual
    val eventChannels: JsonElement,
    val watchAuthored: Boolean,
    val watchCommented: Boolean,
    val dailyDigest: Boolean,
    val dndStartLocal: String?,
    val dndEndLocal: String?,
    val mutedTaskIds: List<@kotlinx.serialization.Contextual UUID>,
    val mutedProjectIds: List<@kotlinx.serialization.Contextual UUID>,
)

@TypeController(type = "WorkOpsNotification")
class NotificationTypeController : GraphQLController<Notification> {
    @Field fun id(n: Notification) = n.id
    @Field fun profileId(n: Notification) = n.profileId
    @Field fun event(n: Notification) = n.event
    @Field fun taskId(n: Notification) = n.taskId
    @Field fun projectId(n: Notification) = n.projectId
    @Field fun actorProfileId(n: Notification) = n.actorProfileId
    @Field fun body(n: Notification) = n.body
    @Field fun link(n: Notification) = n.link
    @Field fun readAt(n: Notification) = n.readAt
    @Field fun createdAt(n: Notification) = n.createdAt
}

@TypeController(type = "WorkOpsNotificationScheme")
class NotificationSchemeTypeController : GraphQLController<NotificationScheme> {
    @Field fun id(s: NotificationScheme) = s.id
    @Field fun name(s: NotificationScheme) = s.name
    @Field fun description(s: NotificationScheme) = s.description
    @Field fun eventRecipients(s: NotificationScheme): JsonElement = s.eventRecipients
    @Field fun version(s: NotificationScheme) = s.version
}

@TypeController(type = "WorkOpsNotificationPreference")
class NotificationPreferenceTypeController : GraphQLController<NotificationPreference> {
    @Field fun profileId(p: NotificationPreference) = p.profileId
    @Field fun eventChannels(p: NotificationPreference): JsonElement = p.eventChannels
    @Field fun watchAuthored(p: NotificationPreference) = p.watchAuthored
    @Field fun watchCommented(p: NotificationPreference) = p.watchCommented
    @Field fun dailyDigest(p: NotificationPreference) = p.dailyDigest
    @Field fun dndStartLocal(p: NotificationPreference) = p.dndStartLocal
    @Field fun dndEndLocal(p: NotificationPreference) = p.dndEndLocal
    @Field fun mutedTaskIds(p: NotificationPreference) = p.mutedTaskIds
    @Field fun mutedProjectIds(p: NotificationPreference) = p.mutedProjectIds
    @Field fun version(p: NotificationPreference) = p.version
}

@TypeController(type = "WorkOpsNotificationSubscription")
class NotificationSubscriptionTypeController : GraphQLController<NotificationSubscription> {
    @Field fun savedFilterId(s: NotificationSubscription) = s.savedFilterId
    @Field fun profileId(s: NotificationSubscription) = s.profileId
    @Field fun cron(s: NotificationSubscription) = s.cron
    @Field fun timeZone(s: NotificationSubscription) = s.timeZone
    @Field fun lastRunAt(s: NotificationSubscription) = s.lastRunAt
}

@TypeController(type = "WorkOpsTaskWatcher")
class TaskWatcherTypeController : GraphQLController<TaskWatcher> {
    @Field fun taskId(w: TaskWatcher) = w.taskId
    @Field fun profileId(w: TaskWatcher) = w.profileId
    @Field fun addedAt(w: TaskWatcher) = w.addedAt
}

object WorkOpsNotificationsQuery

@TypeController
class NotificationsQueryController(
    private val inbox: NotificationInboxService,
    private val schemeService: NotificationSchemeService,
    private val preferenceService: NotificationPreferenceService,
    private val watcherService: TaskWatcherService,
    private val subscriptionService: NotificationSubscriptionService,
    private val taskService: TaskService,
    private val taskPermissions: TaskPermissionEvaluator,
    private val profileService: bosca.profile.profile.service.ProfileService,
) : GraphQLController<WorkOpsNotificationsQuery> {

    private suspend fun resolveProfileId(authentication: AuthenticationContext): UUID? {
        val authenticated = authentication.principal() ?: return null
        val principal = authenticated.asPrincipal()
        val primaryProfileId = principal.primaryProfileId
        if (primaryProfileId != null) return primaryProfileId
        val profile = profileService.getByPrincipal(principal.id).firstOrNull() ?: return null
        return profile.id
    }

    @Field
    suspend fun mine(authentication: AuthenticationContext, offset: Long, limit: Int): List<Notification> {
        val profileId = resolveProfileId(authentication)
            ?: return emptyList()
        return inbox.list(profileId, offset, limit)
    }

    @Field
    suspend fun unread(authentication: AuthenticationContext, offset: Long, limit: Int): List<Notification> {
        val profileId = resolveProfileId(authentication)
            ?: return emptyList()
        return inbox.listUnread(profileId, offset, limit)
    }

    @Field
    suspend fun unreadCount(authentication: AuthenticationContext): Long {
        val profileId = resolveProfileId(authentication)
            ?: return 0L
        return inbox.unreadCount(profileId)
    }

    @Field
    suspend fun schemes(authentication: AuthenticationContext): List<NotificationScheme> {
        return schemeService.list()
    }

    @Field
    suspend fun scheme(authentication: AuthenticationContext, id: UUID): NotificationScheme? {
        return schemeService.getById(id)
    }

    @Field
    suspend fun myPreferences(authentication: AuthenticationContext): NotificationPreference? {
        val profileId = resolveProfileId(authentication) ?: return null
        return preferenceService.get(profileId)
    }

    @Field
    suspend fun watchers(authentication: AuthenticationContext, taskId: UUID): List<TaskWatcher> {
        val task = taskService.getById(taskId) ?: return emptyList()
        if (!taskPermissions.isAllowed(authentication, task, PermissionAction.VIEW)) return emptyList()
        return watcherService.list(taskId)
    }

    @Field
    suspend fun mySubscriptions(authentication: AuthenticationContext): List<NotificationSubscription> {
        val profileId = resolveProfileId(authentication) ?: return emptyList()
        return subscriptionService.listForProfile(profileId)
    }
}

object WorkOpsNotificationsMutation

@TypeController
class NotificationsMutationController(
    private val inbox: NotificationInboxService,
    private val preferenceService: NotificationPreferenceService,
    private val watcherService: TaskWatcherService,
    private val subscriptionService: NotificationSubscriptionService,
    private val taskService: TaskService,
    private val taskPermissions: TaskPermissionEvaluator,
    private val savedFilterService: SavedFilterService,
    private val groupEvaluator: GroupEvaluator,
    private val profileService: bosca.profile.profile.service.ProfileService,
) : GraphQLController<WorkOpsNotificationsMutation> {

    private suspend fun resolveProfileId(authentication: AuthenticationContext): UUID? {
        val authenticated = authentication.principal() ?: return null
        val principal = authenticated.asPrincipal()
        val primaryProfileId = principal.primaryProfileId
        if (primaryProfileId != null) return primaryProfileId
        val profile = profileService.getByPrincipal(principal.id).firstOrNull() ?: return null
        return profile.id
    }

    @Field
    suspend fun markRead(authentication: AuthenticationContext, id: UUID): Boolean {
        val profileId = resolveProfileId(authentication)
            ?: return false
        inbox.markRead(id, profileId)
        return true
    }

    @Field
    suspend fun markAllRead(authentication: AuthenticationContext): Boolean {
        val profileId = resolveProfileId(authentication)
            ?: return false
        inbox.markAllRead(profileId)
        return true
    }

    @Field
    suspend fun updatePreferences(
        authentication: AuthenticationContext,
        input: NotificationPreferenceUpdateInput,
    ): NotificationPreference? {
        val profileId = resolveProfileId(authentication)
            ?: return null
        val typedChannels = mutableMapOf<String, Set<NotificationChannel>>()
        val obj = input.eventChannels as? JsonObject ?: JsonObject(emptyMap())
        for ((key, value) in obj) {
            val channels = (value as? JsonArray)?.mapNotNull {
                runCatching { NotificationChannel.valueOf((it as JsonPrimitive).content) }.getOrNull()
            }?.toSet().orEmpty()
            typedChannels[key] = channels
        }
        return preferenceService.upsert(
            profileId,
            NotificationPreferenceInput(
                eventChannels = typedChannels,
                watchAuthored = input.watchAuthored,
                watchCommented = input.watchCommented,
                dailyDigest = input.dailyDigest,
                dndStartLocal = input.dndStartLocal,
                dndEndLocal = input.dndEndLocal,
                mutedTaskIds = input.mutedTaskIds,
                mutedProjectIds = input.mutedProjectIds,
            )
        )
    }

    @Field
    suspend fun watch(authentication: AuthenticationContext, taskId: UUID): Boolean {
        val task = taskService.getById(taskId) ?: return false
        taskPermissions.verifyAllowed(authentication, task, PermissionAction.VIEW)
        val profileId = resolveProfileId(authentication)
            ?: return false
        watcherService.add(taskId, profileId)
        return true
    }

    @Field
    suspend fun unwatch(authentication: AuthenticationContext, taskId: UUID): Boolean {
        val profileId = resolveProfileId(authentication)
            ?: return false
        watcherService.remove(taskId, profileId)
        return true
    }

    @Field
    suspend fun subscribe(
        authentication: AuthenticationContext,
        savedFilterId: UUID,
        cron: String,
        timeZone: String,
    ): NotificationSubscription {
        val profileId = resolveProfileId(authentication)
            ?: error("subscribe requires a profile")
        val filter = savedFilterService.getById(savedFilterId)
            ?: error("subscribe: saved filter $savedFilterId not found")
        if (filter.ownerProfileId != profileId && !groupEvaluator.hasAdminGroup(authentication)) {
            groupEvaluator.throwUnauthorized()
        }
        return subscriptionService.upsert(savedFilterId, profileId, cron, timeZone)
    }

    @Field
    suspend fun unsubscribe(authentication: AuthenticationContext, savedFilterId: UUID): Boolean {
        val profileId = resolveProfileId(authentication)
            ?: error("unsubscribe requires a profile")
        subscriptionService.delete(savedFilterId, profileId)
        return true
    }
}
