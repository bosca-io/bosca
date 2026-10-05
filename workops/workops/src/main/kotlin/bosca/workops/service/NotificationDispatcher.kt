package bosca.workops.service

import bosca.cache.withRequestCache
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.impersonate
import bosca.serialization.UUID
import bosca.workops.model.notification.NotificationChannel
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationEvent
import bosca.workops.model.notification.NotificationRecipient
import bosca.workops.model.requirement.RequirementParent
import bosca.workops.model.workflow.StatusCategory
import bosca.service.annotation.ServiceImplementation
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

@ServiceImplementation
class NotificationDispatcher(
    private val pubSubService: PubSubService,
    private val schemeService: NotificationSchemeService,
    private val preferenceService: NotificationPreferenceService,
    private val watcherService: TaskWatcherService,
    private val taskService: TaskService,
    private val specService: SpecService,
    private val requirementService: RequirementService,
    private val taskCommentService: TaskCommentService,
    private val specCommentService: SpecCommentService,
    private val requirementCommentService: RequirementCommentService,
    private val projectService: ProjectService,
    private val taskPermissionEvaluator: TaskPermissionEvaluator,
    private val specPermissionEvaluator: SpecPermissionEvaluator,
    private val requirementPermissionEvaluator: RequirementPermissionEvaluator,
    private val projectPermissionEvaluator: ProjectPermissionEvaluator,
    private val inboxService: NotificationInboxService,
    private val outboxService: NotificationOutboxService,
    private val pipelineService: bosca.pipelines.service.PipelineService,
    private val securityService: bosca.security.service.SecurityService,
    private val profileService: ProfileService,
    private val statusService: StatusService,
    private val json: Json,
    private val connectionPool: ConnectionPool,
) : NotificationDeliveryService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * PubSub collectors run on a bare scope with no request context, so every
     * service call in a handler needs an explicitly acquired connection and
     * request cache. Without them, cached service lookups fail and the
     * subscriber retries without delivering the notification.
     */
    private suspend fun <T> withServiceContext(block: suspend () -> T): T {
        val mgr = connectionPool.connection()
        return try {
            withContext(mgr.asCoroutineContext()) {
                withRequestCache { block() }
            }
        } finally {
            withContext(NonCancellable) { mgr.release() }
        }
    }

    init {
        subscribeApprovalGates()
    }

    /**
     * A run parked on an Approval Gate needs its approvers to know — subscribe to the pipeline engine's
     * announcement and turn it into inbox entries for the pipeline's EXECUTE-granted group members.
     */
    private fun subscribeApprovalGates() {
        scope.launch {
            while (true) {
                try {
                    pubSubService.subscribe(
                        bosca.pipelines.model.PipelineAwaitingApproval.CHANNEL,
                        bosca.pipelines.model.PipelineAwaitingApproval.serializer(),
                    ).collect { msg ->
                        while (true) {
                            try {
                                withServiceContext { deliverApprovalRequest(msg.message) }
                                break
                            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.error(
                                    "Approval-gate notification failed; retaining the current event and retrying in 5s: {}",
                                    e.message,
                                    e,
                                )
                                delay(5000)
                            }
                        }
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Approval-gate notification subscriber failed, retrying in 5s: {}", e.message)
                    delay(5000)
                }
            }
        }
    }

    /** Inbox entries for every approver: the members of the pipeline's EXECUTE-granted groups. */
    internal suspend fun deliverApprovalRequest(event: bosca.pipelines.model.PipelineAwaitingApproval) {
        val pipeline = pipelineService.get(event.pipelineId) ?: return
        val profileIds = pipelineService.getPermissions(pipeline)
            .filter { it.action == bosca.security.model.PermissionAction.EXECUTE }
            .map { it.groupId }
            .distinct()
            .flatMap { groupId -> securityService.getPrincipalsByGroup(securityService.getGroupById(groupId)) }
            .mapNotNull { principal ->
                principal.primaryProfileId ?: profileService.getPrimaryProfile(principal)?.id
            }
            .toSet()
        if (profileIds.isEmpty()) {
            log.info("Run {} awaits approval but pipeline '{}' has no EXECUTE-granted approvers to notify", event.runId, pipeline.name)
            return
        }
        val body = event.prompt.ifBlank { "'${pipeline.name}' is awaiting approval" }
        val failures = mutableListOf<Exception>()
        for (profileId in profileIds) {
            try {
                inboxService.addOnce(
                    sourceId = event.runId,
                    profileId = profileId,
                    event = PIPELINE_APPROVAL_REQUESTED,
                    taskId = null,
                    projectId = null,
                    actorProfileId = null,
                    body = body,
                    link = "/pipelines/runs?runId=${event.runId}",
                )
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Approval notification failed for profile {} on run {}", profileId, event.runId, e)
                failures += e
            }
        }
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    override suspend fun deliver(delivery: NotificationDelivery) {
        val eventAssigneeKnown =
            delivery.event == NotificationEvent.TASK_CREATED || delivery.event == NotificationEvent.TASK_UPDATED
        deliver(
            sourceId = delivery.id,
            eventName = delivery.event.name,
            projectId = delivery.projectId,
            taskId = delivery.taskId,
            specId = delivery.specId,
            requirementId = delivery.requirementId,
            actorProfileId = delivery.actorProfileId,
            mentionedProfileIds = delivery.mentionedProfileIds,
            commentId = delivery.commentId,
            eventAssigneeProfileId = delivery.assigneeProfileId,
            eventAssigneeKnown = eventAssigneeKnown,
        )

        if (
            delivery.event == NotificationEvent.TASK_UPDATED &&
            delivery.assigneeChanged &&
            delivery.assigneeProfileId != null
        ) {
            deliver(
                sourceId = delivery.id,
                eventName = NotificationEvent.TASK_ASSIGNED.name,
                projectId = delivery.projectId,
                taskId = delivery.taskId,
                eventAssigneeProfileId = delivery.assigneeProfileId,
                eventAssigneeKnown = true,
            )
        }
        if (delivery.event == NotificationEvent.TASK_TRANSITIONED) {
            derivedTransitionEvent(delivery)?.let { semanticEvent ->
                deliver(
                    sourceId = delivery.id,
                    eventName = semanticEvent.name,
                    projectId = delivery.projectId,
                    taskId = delivery.taskId,
                )
            }
        }
    }

    private suspend fun derivedTransitionEvent(delivery: NotificationDelivery): NotificationEvent? {
        val fromStatus = delivery.fromStatusId?.let { statusService.getById(it) } ?: return null
        val toStatus = delivery.toStatusId?.let { statusService.getById(it) } ?: return null
        val terminal = setOf(StatusCategory.DONE, StatusCategory.CANCELLED)
        return when {
            fromStatus.category in terminal && toStatus.category !in terminal -> NotificationEvent.TASK_REOPENED
            fromStatus.category != StatusCategory.DONE && toStatus.category == StatusCategory.DONE ->
                NotificationEvent.TASK_RESOLVED
            fromStatus.category != StatusCategory.CANCELLED && toStatus.category == StatusCategory.CANCELLED ->
                NotificationEvent.TASK_CLOSED
            else -> null
        }
    }

    internal suspend fun deliver(
        eventName: String,
        projectId: UUID? = null,
        taskId: UUID? = null,
        specId: UUID? = null,
        requirementId: UUID? = null,
        actorProfileId: UUID? = null,
        mentionedProfileIds: Set<UUID> = emptySet(),
        commentId: Long? = null,
        eventAssigneeProfileId: UUID? = null,
        eventAssigneeKnown: Boolean = false,
        sourceId: UUID = UUID.random(),
    ) {
        val task = taskId?.let {
            if (eventName == "TASK_DELETED") taskService.getByIdIncludingDeleted(it)
            else taskService.getById(it) ?: taskService.getByIdIncludingDeleted(it)
        }
        val spec = specId?.let {
            if (eventName == "SPEC_DELETED") specService.getByIdIncludingDeleted(it)
            else specService.getById(it) ?: specService.getByIdIncludingDeleted(it)
        }
        val requirement = requirementId?.let {
            requirementService.getById(it) ?: requirementService.getByIdIncludingDeleted(it)
        }
        val parentTask = requirement?.takeIf { it.parentType == RequirementParent.TASK }
            ?.let { taskService.getById(it.parentId) ?: taskService.getByIdIncludingDeleted(it.parentId) }
        val parentSpec = requirement?.takeIf { it.parentType == RequirementParent.SPEC }
            ?.let { specService.getById(it.parentId) ?: specService.getByIdIncludingDeleted(it.parentId) }
        val resolvedProjectId = projectId
            ?: task?.projectId
            ?: spec?.projectId
            ?: parentTask?.projectId
            ?: parentSpec?.projectId
            ?: return
        val project = projectService.getById(resolvedProjectId) ?: return

        val schemeId = project.defaultNotificationSchemeId ?: SEEDED_DEFAULT_SCHEME_ID
        val scheme = schemeService.typed(schemeId) ?: return
        val recipients = scheme.recipientsByEvent[eventName] ?: return
        if (recipients.isEmpty()) return

        val resolved = mutableSetOf<UUID>()
        fun addResolved(profileId: UUID?) {
            if (profileId != null) resolved.add(profileId)
        }
        for (recipient in recipients) {
            when (recipient) {
                is NotificationRecipient.Reporter -> addResolved(if (task == null) null else task.reporterProfileId)
                is NotificationRecipient.Assignee -> {
                    val taskAssignee = if (task == null) null else task.assigneeProfileId
                    val requirementAssignee = if (requirement == null) null else requirement.assigneeProfileId
                    addResolved(taskAssignee ?: requirementAssignee)
                }

                is NotificationRecipient.CurrentAssignee -> {
                    val profileId = if (eventAssigneeKnown) {
                        eventAssigneeProfileId
                    } else {
                        val taskAssignee = if (task == null) null else task.assigneeProfileId
                        val requirementAssignee = if (requirement == null) null else requirement.assigneeProfileId
                        taskAssignee ?: requirementAssignee
                    }
                    addResolved(profileId)
                }

                is NotificationRecipient.Owner -> {
                    val ownerProfileId = when {
                        spec != null -> spec.ownerProfileId
                        parentSpec != null -> parentSpec.ownerProfileId
                        else -> project.ownerProfileId
                    }
                    resolved.add(ownerProfileId)
                }

                is NotificationRecipient.Watchers -> {
                    val watchedTask = task ?: parentTask
                    if (watchedTask != null) {
                        resolved.addAll(watcherService.list(watchedTask.id).map { it.profileId })
                        resolved.addAll(watchedTask.watcherProfileIds)
                    }
                    val watchedSpec = spec ?: parentSpec
                    if (watchedSpec != null) resolved.addAll(watchedSpec.watcherProfileIds)
                }

                is NotificationRecipient.Profile -> resolved.add(recipient.profileId)
                is NotificationRecipient.MentionedUsers -> resolved.addAll(mentionedProfileIds)
                is NotificationRecipient.Group -> resolved.addAll(resolveGroupProfiles(recipient.groupId))
                is NotificationRecipient.ProjectRole ->
                    resolved.addAll(resolveProjectRoleProfiles(project, recipient.roleId))
                is NotificationRecipient.CustomFieldValue -> {
                    val sourceTask = task ?: parentTask
                    val value = if (sourceTask == null) null else sourceTask.customFieldValues[recipient.fieldKey]
                    if (value != null) resolved.addAll(profileIds(value))
                }
            }
        }
        actorProfileId?.let(resolved::remove)
        if (resolved.isEmpty()) return

        val actorName = actorProfileId?.let { profileService.getById(it).name }
        val entityType: String
        val entityId: UUID
        val entityKey: String
        val title: String
        val linkPath: String
        when {
            task != null -> {
                entityType = "TASK"
                entityId = task.id
                entityKey = task.key
                title = task.summary
                linkPath = "/workops/tasks/${task.id}"
            }
            spec != null -> {
                entityType = "SPEC"
                entityId = spec.id
                entityKey = spec.key
                title = spec.key
                linkPath = "/workops/specs/${spec.id}"
            }
            requirement != null -> {
                entityType = "REQUIREMENT"
                entityId = requirement.id
                entityKey = requirement.key
                title = requirement.key
                linkPath = "/workops/requirements/${requirement.id}"
            }
            else -> {
                entityType = "PROJECT"
                entityId = project.id
                entityKey = project.key
                title = project.name
                linkPath = "/workops"
            }
        }
        val recipientProfiles = profileService.getAllByIds(resolved.toList()).associateBy { it.id }

        val failures = mutableListOf<Exception>()
        for (profileId in resolved) {
            try {
                val profile = recipientProfiles[profileId] ?: continue
                val principalId = profile.principal
                if (principalId == null) {
                    log.warn("Skipping notification {} for profile {} because it has no principal", eventName, profileId)
                    continue
                }
                val authentication = securityService.impersonate(principalId)
                if (!canViewEntity(authentication, eventName, project, task, spec, requirement)) continue
                val body = visibleBody(
                    authentication = authentication,
                    profileId = profileId,
                    eventName = eventName,
                    commentId = commentId,
                    task = task,
                    spec = spec,
                    requirement = requirement,
                ) ?: continue
                val pref = preferenceService.get(profileId)
                val mutedTaskId = when {
                    task != null -> task.id
                    parentTask != null -> parentTask.id
                    else -> null
                }
                if (pref != null && (
                        resolvedProjectId in pref.mutedProjectIds ||
                            (mutedTaskId != null && mutedTaskId in pref.mutedTaskIds)
                    )
                ) {
                    continue
                }
                val configuredChannels = if (pref != null) {
                    preferenceService.decodedChannels(pref, eventName)
                } else {
                    DEFAULT_NOTIFICATION_CHANNELS
                }
                if (configuredChannels.isEmpty()) continue
                val externalPlan = pref?.let { externalDeliveryPlan(profileId, it) } ?: ExternalDeliveryPlan.IMMEDIATE
                val payload = buildJsonObject {
                    put("event", eventName)
                    put("projectName", project.name)
                    put("entityType", entityType)
                    put("entityId", entityId.toString())
                    put("entityKey", entityKey)
                    put("title", title)
                    put("body", body)
                    actorName?.let { put("actorName", it) }
                    put("linkPath", linkPath)
                    taskId?.let { put("taskId", it.toString()) }
                    specId?.let { put("specId", it.toString()) }
                    requirementId?.let { put("requirementId", it.toString()) }
                    put("projectId", resolvedProjectId.toString())
                    actorProfileId?.let { put("actorProfileId", it.toString()) }
                }
                val encodedPayload = json.encodeToString(JsonObject.serializer(), payload)

                for (channel in configuredChannels) {
                    try {
                        when (channel) {
                            NotificationChannel.IN_APP -> inboxService.addOnce(
                                sourceId = sourceId,
                                profileId = profileId,
                                event = eventName,
                                taskId = taskId,
                                projectId = resolvedProjectId,
                                actorProfileId = actorProfileId,
                                body = body,
                                link = linkPath,
                            )

                            NotificationChannel.EMAIL,
                            NotificationChannel.WEBHOOK,
                            NotificationChannel.SLACK -> {
                                if (externalPlan.suppressed) continue
                                outboxService.enqueueOnce(
                                    sourceId = sourceId,
                                    event = eventName,
                                    channel = channel,
                                    target = profileId.toString(),
                                    payload = encodedPayload,
                                    availableAt = externalPlan.availableAt,
                                    digest = externalPlan.digest,
                                )
                            }
                        }
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error(
                            "Notification {} delivery failed for profile {} on {}: {}",
                            channel,
                            profileId,
                            eventName,
                            e.message,
                            e,
                        )
                        failures += e
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Notification delivery failed for profile {} on {}: {}", profileId, eventName, e.message, e)
                failures += e
            }
        }
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    private suspend fun canViewEntity(
        authentication: AuthenticationContext,
        eventName: String,
        project: bosca.workops.model.project.Project,
        task: bosca.workops.model.task.Task?,
        spec: bosca.workops.model.spec.Spec?,
        requirement: bosca.workops.model.requirement.Requirement?,
    ): Boolean {
        if (eventName.endsWith("_DELETED")) {
            return projectPermissionEvaluator.isAllowed(authentication, project, PermissionAction.VIEW)
        }
        return when {
            task != null -> taskPermissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW)
            spec != null -> specPermissionEvaluator.isAllowed(authentication, spec, PermissionAction.VIEW)
            requirement != null -> requirementPermissionEvaluator.isAllowed(authentication, requirement, PermissionAction.VIEW)
            else -> projectPermissionEvaluator.isAllowed(authentication, project, PermissionAction.VIEW)
        }
    }

    private suspend fun visibleBody(
        authentication: AuthenticationContext,
        profileId: UUID,
        eventName: String,
        commentId: Long?,
        task: bosca.workops.model.task.Task?,
        spec: bosca.workops.model.spec.Spec?,
        requirement: bosca.workops.model.requirement.Requirement?,
    ): String? {
        val fallback = eventName.lowercase().replace('_', ' ')
        if (commentId == null || eventName.endsWith("_DELETED")) return fallback
        return when {
            task != null -> {
                val manager = taskPermissionEvaluator.isAllowed(authentication, task, PermissionAction.MANAGE)
                taskCommentService.get(task.id, commentId, profileId, manager)?.content
            }
            spec != null -> {
                if (specPermissionEvaluator.isAllowed(authentication, spec, PermissionAction.MANAGE)) {
                    specCommentService.getManager(spec.id, commentId)?.content
                } else {
                    specCommentService.getForProfile(spec.id, commentId, profileId)?.content
                }
            }
            requirement != null -> {
                if (requirementPermissionEvaluator.isAllowed(authentication, requirement, PermissionAction.MANAGE)) {
                    requirementCommentService.getManager(requirement.id, commentId)?.content
                } else {
                    requirementCommentService.getForProfile(requirement.id, commentId, profileId)?.content
                }
            }
            else -> null
        }
    }

    private suspend fun externalDeliveryPlan(
        profileId: UUID,
        preference: bosca.workops.model.notification.NotificationPreference,
    ): ExternalDeliveryPlan {
        if (!preference.dailyDigest && (preference.dndStartLocal == null || preference.dndEndLocal == null)) {
            return ExternalDeliveryPlan.IMMEDIATE
        }
        val attributes = profileService.getAttributes(profileId)
        val zoneName = attributes.getAttributeString(PROFILE_TIMEZONE_ATTRIBUTE, PROFILE_ATTRIBUTE_VALUE)
            ?: attributes.getAttributeString(PROFILE_TIMEZONE_ATTRIBUTE, LEGACY_PROFILE_TIMEZONE_VALUE)
        val zone = zoneName?.let { value ->
            runCatching { ZoneId.of(value) }.getOrElse {
                log.warn("Ignoring invalid timezone '{}' for profile {}", value, profileId)
                ZoneOffset.UTC
            }
        } ?: ZoneOffset.UTC
        val now = Instant.now()
        if (preference.dailyDigest) {
            return ExternalDeliveryPlan(availableAt = nextDigestAt(zone, now), digest = true)
        }
        val start = runCatching { LocalTime.parse(preference.dndStartLocal) }.getOrElse {
            log.warn("Ignoring invalid DND start for profile {}: {}", profileId, it.message)
            return ExternalDeliveryPlan.IMMEDIATE
        }
        val end = runCatching { LocalTime.parse(preference.dndEndLocal) }.getOrElse {
            log.warn("Ignoring invalid DND end for profile {}: {}", profileId, it.message)
            return ExternalDeliveryPlan.IMMEDIATE
        }
        if (!isInDndWindow(start, end, zone, now)) return ExternalDeliveryPlan.IMMEDIATE
        if (start == end) return ExternalDeliveryPlan.SUPPRESSED
        return ExternalDeliveryPlan(availableAt = dndEndsAt(start, end, zone, now))
    }

    private suspend fun resolveGroupProfiles(groupId: UUID): Set<UUID> {
        return securityService.getPrincipalsByGroup(securityService.getGroupById(groupId))
            .mapNotNullTo(linkedSetOf()) { principal ->
                principal.primaryProfileId ?: profileService.getPrimaryProfile(principal)?.id
            }
    }

    /** Project roles were replaced by project permission groups in V21; role ids now name one of those groups. */
    private suspend fun resolveProjectRoleProfiles(
        project: bosca.workops.model.project.Project,
        roleId: UUID,
    ): Set<UUID> {
        val projectGroupIds = projectService.getPermissions(project).mapTo(mutableSetOf()) { it.groupId }
        return if (roleId !in projectGroupIds) {
            log.warn("Notification scheme role {} is not a permission group on project {}", roleId, project.id)
            emptySet()
        } else {
            resolveGroupProfiles(roleId)
        }
    }

    private fun profileIds(value: kotlinx.serialization.json.JsonElement): Set<UUID> = when (value) {
        is JsonPrimitive -> value.contentOrNull
            ?.let { runCatching { UUID.parse(it) }.getOrNull() }
            ?.let(::setOf)
            .orEmpty()
        is JsonArray -> value.flatMapTo(linkedSetOf(), ::profileIds)
        else -> emptySet()
    }

    internal fun close() {
        scope.cancel()
    }

    private data class ExternalDeliveryPlan(
        val availableAt: java.time.OffsetDateTime? = null,
        val digest: Boolean = false,
        val suppressed: Boolean = false,
    ) {
        companion object {
            val IMMEDIATE = ExternalDeliveryPlan()
            val SUPPRESSED = ExternalDeliveryPlan(suppressed = true)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NotificationDispatcher::class.java)

        /** Inbox event key for "a run is parked on an Approval Gate". */
        const val PIPELINE_APPROVAL_REQUESTED = "PIPELINE_APPROVAL_REQUESTED"

        private const val PROFILE_TIMEZONE_ATTRIBUTE = "bosca.profiles.timezone"
        private const val PROFILE_ATTRIBUTE_VALUE = "value"
        private const val LEGACY_PROFILE_TIMEZONE_VALUE = "timezone"

        private val SEEDED_DEFAULT_SCHEME_ID: UUID = UUID.parse("b0000000-0000-0000-0000-000000000001")
    }
}

internal fun isInDndWindow(start: LocalTime, end: LocalTime, zone: ZoneId, now: Instant): Boolean {
    val localTime = now.atZone(zone).toLocalTime()
    return when {
        start == end -> true
        start < end -> localTime >= start && localTime < end
        else -> localTime >= start || localTime < end
    }
}

internal fun nextDigestAt(zone: ZoneId, now: Instant): java.time.OffsetDateTime {
    val localNow = now.atZone(zone)
    var delivery = localNow.toLocalDate().atTime(LocalTime.of(9, 0)).atZone(zone)
    if (!delivery.isAfter(localNow)) delivery = delivery.plusDays(1)
    return delivery.toOffsetDateTime()
}

internal fun dndEndsAt(
    start: LocalTime,
    end: LocalTime,
    zone: ZoneId,
    now: Instant,
): java.time.OffsetDateTime {
    require(start != end) { "An all-day DND window has no delivery boundary" }
    val localNow = now.atZone(zone)
    val endDate = when {
        start < end -> localNow.toLocalDate()
        localNow.toLocalTime() >= start -> localNow.toLocalDate().plusDays(1)
        else -> localNow.toLocalDate()
    }
    return endDate.atTime(end).atZone(zone).toOffsetDateTime()
}
