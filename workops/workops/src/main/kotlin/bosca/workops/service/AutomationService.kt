package bosca.workops.service

import bosca.profile.profile.service.ProfileService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.automation.Action
import bosca.workops.model.automation.AutomationOutcome
import bosca.workops.model.automation.AutomationRule
import bosca.workops.model.automation.AutomationScope
import bosca.workops.model.automation.FailureMode
import bosca.workops.model.automation.Trigger
import bosca.workops.model.notification.NotificationChannel
import bosca.workops.model.task.Task
import bosca.workops.model.workflow.Condition
import bosca.workops.repository.AutomationExecutionLogParams
import bosca.workops.repository.AutomationExecutionLogRepository
import bosca.workops.repository.AutomationLoopGuardRepository
import bosca.workops.repository.AutomationRuleInsertParams
import org.slf4j.LoggerFactory
import bosca.workops.repository.AutomationRuleRepository
import bosca.workops.repository.AutomationRuleUpdateParams
import bosca.workops.model.links.TaskLinkInput
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.model.task.UpdateTaskInput
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun JsonObject.stringOrNull(key: String): String? {
    val value = this[key] ?: return null
    return value.jsonPrimitive.contentOrNull
}

@ServiceImplementation
class AutomationRuleServiceImpl(
    private val repository: AutomationRuleRepository,
    private val json: Json,
) : AutomationRuleService {

    override suspend fun getById(id: UUID) = repository.getById(id)

    override suspend fun listInScope(scope: AutomationScope, scopeId: UUID?) =
        repository.listInScope(scope.name, scopeId)

    override suspend fun listEnabledForScope(scope: AutomationScope, scopeId: UUID?) =
        repository.listEnabled(scope.name, scopeId)

    override suspend fun create(input: AutomationRuleInput): AutomationRule = repository.add(
        AutomationRuleInsertParams(
            scope = input.scope.name,
            scopeId = input.scopeId,
            name = input.name,
            description = input.description,
            enabled = input.enabled,
            trigger = json.encodeToString(Trigger.serializer(), input.trigger),
            conditions = json.encodeToString(ListSerializer(Condition.serializer()), input.conditions),
            actions = json.encodeToString(ListSerializer(Action.serializer()), input.actions),
            runAsProfileId = input.runAsProfileId,
            failureMode = input.failureMode.name,
            executionLogRetentionDays = input.executionLogRetentionDays,
            maxFiresPerTaskPerHour = input.maxFiresPerTaskPerHour,
        )
    )

    override suspend fun update(id: UUID, input: AutomationRuleInput, expectedVersion: Long): AutomationRule {
        val updated = repository.update(
            AutomationRuleUpdateParams(
                id = id,
                name = input.name,
                description = input.description,
                enabled = input.enabled,
                trigger = json.encodeToString(Trigger.serializer(), input.trigger),
                conditions = json.encodeToString(ListSerializer(Condition.serializer()), input.conditions),
                actions = json.encodeToString(ListSerializer(Action.serializer()), input.actions),
                runAsProfileId = input.runAsProfileId,
                failureMode = input.failureMode.name,
                executionLogRetentionDays = input.executionLogRetentionDays,
                maxFiresPerTaskPerHour = input.maxFiresPerTaskPerHour,
                expectedVersion = expectedVersion,
            )
        ) ?: throw WorkOpsNotFoundException("AutomationRule", id.toString())
        return updated
    }

    override suspend fun delete(id: UUID) = repository.delete(id)
}

@ServiceImplementation
class AutomationDispatcherImpl(
    private val ruleRepo: AutomationRuleRepository,
    private val executor: AutomationExecutor,
    private val json: Json,
) : AutomationDispatcher {

    private suspend fun candidates(projectId: UUID, programId: UUID?, portfolioId: UUID?): List<AutomationRule> {
        val rules = mutableListOf<AutomationRule>()
        rules.addAll(ruleRepo.listEnabled(AutomationScope.GLOBAL.name, null))
        if (portfolioId != null) rules.addAll(ruleRepo.listEnabled(AutomationScope.PORTFOLIO.name, portfolioId))
        if (programId != null) rules.addAll(ruleRepo.listEnabled(AutomationScope.PROGRAM.name, programId))
        rules.addAll(ruleRepo.listEnabled(AutomationScope.PROJECT.name, projectId))
        return rules
    }

    private inline fun <reified T : Trigger> filterByTrigger(
        rules: List<AutomationRule>,
        match: (T) -> Boolean,
    ): List<Pair<AutomationRule, T>> {
        return rules.mapNotNull { rule ->
            val trigger = try {
                json.decodeFromJsonElement(Trigger.serializer(), rule.trigger)
            } catch (e: Exception) {
                log.error("Failed to decode trigger for rule {}: {}", rule.id, e.message)
                return@mapNotNull null
            }
            if (trigger !is T) return@mapNotNull null
            if (!match(trigger)) return@mapNotNull null
            rule to trigger
        }
    }

    override suspend fun fireTaskCreated(task: Task, projectId: UUID, programId: UUID?, portfolioId: UUID?) {
        val all = candidates(projectId, programId, portfolioId)
        for ((rule, _) in filterByTrigger<Trigger.TaskCreated>(all) { true }) {
            try {
                executor.run(rule, AutomationContext(triggeringTask = task, projectId = projectId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Automation rule {} failed for TaskCreated on task {}: {}", rule.id, task.id, e.message, e)
            }
        }
    }

    override suspend fun fireTaskUpdated(task: Task, projectId: UUID, programId: UUID?, portfolioId: UUID?, changedKeys: Set<String>) {
        val all = candidates(projectId, programId, portfolioId)
        for ((rule, trig) in filterByTrigger<Trigger.TaskUpdated>(all) { trig ->
            trig.fieldKeys.isEmpty() || trig.fieldKeys.any { it in changedKeys }
        }) {
            try {
                executor.run(rule, AutomationContext(triggeringTask = task, projectId = projectId, changedKeys = changedKeys))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Automation rule {} failed for TaskUpdated on task {}: {}", rule.id, task.id, e.message, e)
            }
        }
    }

    override suspend fun fireTaskTransitioned(
        task: Task, projectId: UUID, programId: UUID?, portfolioId: UUID?,
        fromStatusId: UUID?, toStatusId: UUID,
    ) {
        val all = candidates(projectId, programId, portfolioId)
        for ((rule, _) in filterByTrigger<Trigger.TaskTransitioned>(all) { trig ->
            (trig.fromStatusIds.isEmpty() || (fromStatusId != null && fromStatusId in trig.fromStatusIds)) &&
                (trig.toStatusIds.isEmpty() || toStatusId in trig.toStatusIds)
        }) {
            try {
                executor.run(rule, AutomationContext(triggeringTask = task, projectId = projectId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Automation rule {} failed for TaskTransitioned on task {}: {}", rule.id, task.id, e.message, e)
            }
        }
    }

    override suspend fun fireTaskCommented(task: Task, projectId: UUID, programId: UUID?, portfolioId: UUID?) {
        val all = candidates(projectId, programId, portfolioId)
        for ((rule, _) in filterByTrigger<Trigger.TaskCommented>(all) { true }) {
            try {
                executor.run(rule, AutomationContext(triggeringTask = task, projectId = projectId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Automation rule {} failed for TaskCommented on task {}: {}", rule.id, task.id, e.message, e)
            }
        }
    }

    override suspend fun fireTaskDeleted(task: Task, projectId: UUID, programId: UUID?, portfolioId: UUID?) {
        val all = candidates(projectId, programId, portfolioId)
        for ((rule, _) in filterByTrigger<Trigger.TaskDeleted>(all) { true }) {
            try {
                executor.run(rule, AutomationContext(triggeringTask = task, projectId = projectId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Automation rule {} failed for TaskDeleted on task {}: {}", rule.id, task.id, e.message, e)
            }
        }
    }

    override suspend fun fireSlaBreached(task: Task, projectId: UUID, programId: UUID?, portfolioId: UUID?, slaGoalId: UUID) {
        val all = candidates(projectId, programId, portfolioId)
        for ((rule, _) in filterByTrigger<Trigger.SlaBreached>(all) { trig ->
            trig.slaGoalIds.isEmpty() || slaGoalId in trig.slaGoalIds
        }) {
            try {
                executor.run(rule, AutomationContext(triggeringTask = task, projectId = projectId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Automation rule {} failed for SlaBreached on task {}: {}", rule.id, task.id, e.message, e)
            }
        }
    }

    override suspend fun fireSlaAtRisk(task: Task, projectId: UUID, programId: UUID?, portfolioId: UUID?, slaGoalId: UUID) {
        val all = candidates(projectId, programId, portfolioId)
        for ((rule, _) in filterByTrigger<Trigger.SlaAtRisk>(all) { trig ->
            trig.slaGoalIds.isEmpty() || slaGoalId in trig.slaGoalIds
        }) {
            try {
                executor.run(rule, AutomationContext(triggeringTask = task, projectId = projectId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Automation rule {} failed for SlaAtRisk on task {}: {}", rule.id, task.id, e.message, e)
            }
        }
    }

    override suspend fun fireArtifactPublished(projectId: UUID, programId: UUID?, portfolioId: UUID?, artifactType: String, coordinates: String) {
        val all = candidates(projectId, programId, portfolioId)
        for ((rule, _) in filterByTrigger<Trigger.ArtifactPublished>(all) { trig ->
            (trig.projectId == null || trig.projectId == projectId) &&
                (trig.artifactTypes.isEmpty() || artifactType in trig.artifactTypes)
        }) {
            try {
                executor.run(rule, AutomationContext(
                    triggeringTask = null, projectId = projectId,
                    eventMetadata = mapOf("artifactType" to artifactType, "coordinates" to coordinates),
                ))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.error("Automation rule {} failed for ArtifactPublished: {}", rule.id, e.message, e) }
        }
    }

    override suspend fun firePipelineFailed(projectId: UUID, programId: UUID?, portfolioId: UUID?, pipelineId: String) {
        val all = candidates(projectId, programId, portfolioId)
        for ((rule, _) in filterByTrigger<Trigger.PipelineFailed>(all) { trig ->
            (trig.projectId == null || trig.projectId == projectId) &&
                (trig.pipelineIds.isEmpty() || pipelineId in trig.pipelineIds)
        }) {
            try {
                executor.run(rule, AutomationContext(
                    triggeringTask = null, projectId = projectId,
                    eventMetadata = mapOf("pipelineId" to pipelineId),
                ))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.error("Automation rule {} failed for PipelineFailed: {}", rule.id, e.message, e) }
        }
    }

    override suspend fun firePipelineCompleted(projectId: UUID, programId: UUID?, portfolioId: UUID?, pipelineId: String) {
        val all = candidates(projectId, programId, portfolioId)
        for ((rule, _) in filterByTrigger<Trigger.PipelineCompleted>(all) { trig ->
            (trig.projectId == null || trig.projectId == projectId) &&
                (trig.pipelineIds.isEmpty() || pipelineId in trig.pipelineIds)
        }) {
            try {
                executor.run(rule, AutomationContext(
                    triggeringTask = null, projectId = projectId,
                    eventMetadata = mapOf("pipelineId" to pipelineId),
                ))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.error("Automation rule {} failed for PipelineCompleted: {}", rule.id, e.message, e) }
        }
    }

    override suspend fun fireDependencyOutdated(consumerProjectId: UUID, programId: UUID?, portfolioId: UUID?) {
        val all = candidates(consumerProjectId, programId, portfolioId)
        for ((rule, _) in filterByTrigger<Trigger.DependencyOutdated>(all) { trig ->
            trig.consumerProjectId == null || trig.consumerProjectId == consumerProjectId
        }) {
            try {
                executor.run(rule, AutomationContext(triggeringTask = null, projectId = consumerProjectId))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.error("Automation rule {} failed for DependencyOutdated: {}", rule.id, e.message, e) }
        }
    }

    override suspend fun fireEnvironmentDeploymentFailed(projectId: UUID, programId: UUID?, portfolioId: UUID?, environmentId: UUID) {
        val all = candidates(projectId, programId, portfolioId)
        for ((rule, _) in filterByTrigger<Trigger.EnvironmentDeploymentFailed>(all) { trig ->
            trig.environmentId == null || trig.environmentId == environmentId
        }) {
            try {
                executor.run(rule, AutomationContext(
                    triggeringTask = null, projectId = projectId,
                    eventMetadata = mapOf("environmentId" to environmentId.toString()),
                ))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.error("Automation rule {} failed for EnvironmentDeploymentFailed: {}", rule.id, e.message, e) }
        }
    }

    override suspend fun fireEnvironmentUnhealthy(projectId: UUID, programId: UUID?, portfolioId: UUID?, environmentId: UUID) {
        val all = candidates(projectId, programId, portfolioId)
        for ((rule, _) in filterByTrigger<Trigger.EnvironmentUnhealthy>(all) { trig ->
            trig.environmentId == null || trig.environmentId == environmentId
        }) {
            try {
                executor.run(rule, AutomationContext(
                    triggeringTask = null, projectId = projectId,
                    eventMetadata = mapOf("environmentId" to environmentId.toString()),
                ))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.error("Automation rule {} failed for EnvironmentUnhealthy: {}", rule.id, e.message, e) }
        }
    }

    override suspend fun fireEnvironmentPromotionReady(projectId: UUID, programId: UUID?, portfolioId: UUID?, environmentId: UUID) {
        val all = candidates(projectId, programId, portfolioId)
        for ((rule, _) in filterByTrigger<Trigger.EnvironmentPromotionReady>(all) { trig ->
            trig.environmentId == null || trig.environmentId == environmentId
        }) {
            try {
                executor.run(rule, AutomationContext(
                    triggeringTask = null, projectId = projectId,
                    eventMetadata = mapOf("environmentId" to environmentId.toString()),
                ))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.error("Automation rule {} failed for EnvironmentPromotionReady: {}", rule.id, e.message, e) }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AutomationDispatcherImpl::class.java)
    }
}

@ServiceImplementation
class AutomationExecutorImpl(
    private val executionLogRepo: AutomationExecutionLogRepository,
    private val loopGuard: AutomationLoopGuardRepository,
    private val taskService: TaskService,
    private val taskLinkService: TaskLinkService,
    private val notificationOutbox: NotificationOutboxService,
    private val profileService: ProfileService,
    private val json: Json,
) : AutomationExecutor {

    private val workflowEvaluator = WorkflowEvaluator()

    override suspend fun run(rule: AutomationRule, context: AutomationContext): AutomationOutcome {
        val started = OffsetDateTime.now()
        val taskId = context.triggeringTask?.id

        // Loop guard short-circuit.
        if (taskId != null) {
            val current = loopGuard.currentCount(rule.id, taskId) ?: 0
            if (current >= rule.maxFiresPerTaskPerHour) {
                logFinish(rule, taskId, AutomationOutcome.LOOP_GUARD_TRIPPED, started, "loop guard tripped")
                return AutomationOutcome.LOOP_GUARD_TRIPPED
            }
        }

        val actor = try {
            val profile = profileService.getById(rule.runAsProfileId)
            AutomationActor(
                principalId = profile.principal
                    ?: error("automation run-as profile ${profile.id} is not linked to a principal"),
                profileId = profile.id,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFinish(rule, taskId, AutomationOutcome.ACTION_FAILED, started, e.message ?: e.javaClass.simpleName)
            return AutomationOutcome.ACTION_FAILED
        }

        // Gate on the rule's conditions before any action runs, reusing the workflow condition
        // engine. Conditions are task-oriented, so they are only evaluated for task-backed events;
        // the automation runs as the rule's run-as profile, which is the acting identity here.
        context.triggeringTask?.let { task ->
            val conditions = try {
                val conditionArray = rule.conditions as? JsonArray
                if (conditionArray == null) emptyList()
                else json.decodeFromJsonElement(ListSerializer(Condition.serializer()), conditionArray)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFinish(rule, taskId, AutomationOutcome.INTERNAL_ERROR, started, "decode conditions failed: ${e.message}")
                return AutomationOutcome.INTERNAL_ERROR
            }
            if (conditions.isNotEmpty()) {
                val workflowContext = WorkflowContext(
                    task = task,
                    actingPrincipalId = actor.principalId,
                    actingProfileId = actor.profileId,
                    comment = null,
                    resolutionId = null,
                    unresolvedSubtaskCount = 0,
                )
                if (!workflowEvaluator.conditionsHold(conditions, workflowContext)) {
                    logFinish(rule, taskId, AutomationOutcome.SKIPPED, started, "conditions not met")
                    return AutomationOutcome.SKIPPED
                }
            }
        }

        val actions = try {
            json.decodeFromJsonElement(ListSerializer(Action.serializer()), rule.actions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFinish(rule, taskId, AutomationOutcome.INTERNAL_ERROR, started, "decode actions failed: ${e.message}")
            return AutomationOutcome.INTERNAL_ERROR
        }

        // Reserve this fire before actions run. Task mutations synchronously dispatch cascading
        // automation after commit, so the nested execution must observe the outer fire count.
        if (taskId != null) loopGuard.bump(rule.id, taskId)

        var outcome = AutomationOutcome.OK
        var errorMessage: String? = null
        var currentTask = context.triggeringTask
        for (action in actions) {
            try {
                currentTask = executeAction(action, rule, currentTask, actor)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                outcome = if (e is PendingPhaseImplementationException) AutomationOutcome.NOT_IMPLEMENTED
                else AutomationOutcome.ACTION_FAILED
                if (errorMessage == null) errorMessage = message
                if (rule.failureMode == FailureMode.STOP_ON_ERROR) break
            }
        }

        logFinish(rule, taskId, outcome, started, errorMessage)
        return outcome
    }

    private suspend fun executeAction(
        action: Action,
        rule: AutomationRule,
        task: Task?,
        actor: AutomationActor,
    ): Task? {
        return when (action) {
            is Action.AddComment -> {
                // Phase 8.2 keeps AddComment lightweight — drops a
                // workops.notification_outbox row tagged "AUTOMATION"
                // so a comment-write worker can post it (Phase 11
                // wires the comment service path directly).
                if (task == null) return null
                notificationOutbox.enqueue(
                    "AUTOMATION_COMMENT",
                    task.id.toString(),
                    json.encodeToString(JsonElement.serializer(), buildJsonObject {
                        put("ruleId", JsonPrimitive(rule.id.toString()))
                        put("taskId", JsonPrimitive(task.id.toString()))
                        put("template", JsonPrimitive(action.template))
                        put("runAs", JsonPrimitive(actor.profileId.toString()))
                    })
                )
                task
            }
            is Action.SendWebhook -> {
                notificationOutbox.enqueue(
                    "WEBHOOK", action.url,
                    json.encodeToString(JsonElement.serializer(), buildJsonObject {
                        put("ruleId", JsonPrimitive(rule.id.toString()))
                        put("body", JsonPrimitive(action.bodyTemplate))
                        // headers are per-rule; serialized as a JsonObject
                        val headers = buildJsonObject {
                            action.headers.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                        }
                        put("headers", headers)
                    })
                )
                task
            }
            is Action.SendEmail -> {
                val recipientIds = resolveEmailRecipients(action.toExpression)
                require(recipientIds.isNotEmpty()) {
                    "SendEmail recipient expression did not resolve to any verified profiles"
                }
                recipientIds.forEach { recipientId ->
                    notificationOutbox.enqueueOnce(
                        sourceId = UUID.random(),
                        event = NotificationChannelDeliveryServiceImpl.AUTOMATION_EMAIL_EVENT,
                        channel = NotificationChannel.EMAIL,
                        target = recipientId.toString(),
                        payload = json.encodeToString(
                            JsonElement.serializer(),
                            buildJsonObject {
                                put("subject", JsonPrimitive(action.subject))
                                put("body", JsonPrimitive(action.body))
                            },
                        ),
                    )
                }
                task
            }
            is Action.SendSlackMessage -> {
                notificationOutbox.enqueue(
                    "SLACK", action.channel,
                    json.encodeToString(JsonElement.serializer(), buildJsonObject {
                        put("body", JsonPrimitive(action.body))
                        put("ruleId", JsonPrimitive(rule.id.toString()))
                    })
                )
                task
            }
            is Action.EditTask -> {
                val task = task ?: return null
                val fields = action.fields.jsonObject
                val input = UpdateTaskInput(
                    summary = fields.stringOrNull("summary"),
                    descriptionMarkdown = fields.stringOrNull("descriptionMarkdown"),
                    taskTypeId = fields.stringOrNull("taskTypeId")?.let { UUID.parse(it) },
                    assigneeProfileId = fields.stringOrNull("assigneeProfileId")?.let { UUID.parse(it) },
                    clearAssignee = fields.stringOrNull("clearAssignee")?.toBooleanStrictOrNull() ?: false,
                    priorityId = fields.stringOrNull("priorityId")?.let { UUID.parse(it) },
                    dueDate = fields.stringOrNull("dueDate")?.let { OffsetDateTime.parse(it) },
                    clearDueDate = fields.stringOrNull("clearDueDate")?.toBooleanStrictOrNull() ?: false,
                    startDate = fields.stringOrNull("startDate")?.let { OffsetDateTime.parse(it) },
                    clearStartDate = fields.stringOrNull("clearStartDate")?.toBooleanStrictOrNull() ?: false,
                    expectedVersion = task.version,
                )
                taskService.update(task.id, input, actor.principalId, actor.profileId)
            }
            is Action.CreateTask -> {
                val fields = action.fields.jsonObject
                val summary = fields.stringOrNull("summary")
                    ?: throw IllegalArgumentException("CreateTask requires a 'summary' field")
                val input = CreateTaskInput(
                    projectId = action.projectId,
                    taskTypeId = fields.stringOrNull("taskTypeId")?.let { UUID.parse(it) },
                    statusId = fields.stringOrNull("statusId")?.let { UUID.parse(it) },
                    priorityId = fields.stringOrNull("priorityId")?.let { UUID.parse(it) },
                    summary = summary,
                    descriptionMarkdown = fields.stringOrNull("descriptionMarkdown"),
                    assigneeProfileId = fields.stringOrNull("assigneeProfileId")?.let { UUID.parse(it) },
                )
                taskService.create(input, actor.principalId, actor.profileId, actor.profileId)
                task
            }
            is Action.TransitionTask -> {
                val task = task ?: return null
                taskService.transition(
                    id = task.id,
                    transitionId = action.transitionId,
                    expectedVersion = task.version,
                    actingPrincipalId = actor.principalId,
                    actingProfileId = actor.profileId,
                )
            }
            is Action.SetFieldValue -> {
                val task = task ?: return null
                val merged = buildJsonObject {
                    task.customFieldValues.forEach { (k, v) -> put(k, v) }
                    put(action.fieldKey, JsonPrimitive(action.expression))
                }
                taskService.setCustomFieldValues(
                    id = task.id,
                    customFieldValues = merged,
                    expectedVersion = task.version,
                    actingPrincipalId = actor.principalId,
                    actingProfileId = actor.profileId,
                )
            }
            is Action.AssignTo -> {
                val task = task ?: return null
                val profileId = UUID.parse(action.profileExpression)
                val input = UpdateTaskInput(
                    assigneeProfileId = profileId,
                    expectedVersion = task.version,
                )
                taskService.update(task.id, input, actor.principalId, actor.profileId)
            }
            is Action.LinkTasks -> {
                val task = task ?: return null
                val targetId = UUID.parse(action.targetExpression)
                taskLinkService.link(
                    TaskLinkInput(
                        linkTypeId = action.linkTypeId,
                        sourceTaskId = task.id,
                        targetTaskId = targetId,
                    ),
                    actingPrincipalId = actor.principalId,
                )
                task
            }
            is Action.CreateSubtask,
            is Action.Sleep,
            is Action.Branch,
            is Action.ForEach,
            is Action.RunScript,
            is Action.Lookup,
            is Action.AssignToOnCallRotation -> throw PendingPhaseImplementationException(
                variant = "Action.${action::class.simpleName}", owningPhase = 11,
            )
        }
    }

    private suspend fun resolveEmailRecipients(expression: String): Set<UUID> = buildSet {
        for (token in expression.split(',', ';').map(String::trim).filter(String::isNotEmpty)) {
            val profileId = runCatching { UUID.parse(token) }.getOrNull()
            if (profileId != null) {
                profileService.getById(profileId)
                add(profileId)
            } else {
                addAll(profileService.getProfilesByEmail(token).map { it.id })
            }
        }
    }

    private data class AutomationActor(
        val principalId: UUID,
        val profileId: UUID,
    )

    private suspend fun logFinish(
        rule: AutomationRule,
        taskId: UUID?,
        outcome: AutomationOutcome,
        startedAt: OffsetDateTime,
        errorMessage: String?,
    ) {
        val finished = OffsetDateTime.now()
        val durationMs = java.time.Duration.between(startedAt.toInstant(), finished.toInstant()).toMillis()
        executionLogRepo.add(
            AutomationExecutionLogParams(
                ruleId = rule.id,
                taskId = taskId,
                outcome = outcome.name,
                startedAt = startedAt,
                finishedAt = finished,
                durationMs = durationMs,
                errorMessage = errorMessage,
            )
        )
    }
}

@ServiceImplementation
class AutomationExecutionLogServiceImpl(
    private val repository: AutomationExecutionLogRepository,
) : AutomationExecutionLogService {
    override suspend fun listForRule(ruleId: UUID, offset: Long, limit: Int) =
        repository.listForRule(ruleId, offset, limit.coerceIn(1, 200))
}
