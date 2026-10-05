package bosca.workops.service

import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.automation.Action
import bosca.workops.model.automation.AutomationOutcome
import bosca.workops.model.automation.AutomationRule
import bosca.workops.model.automation.AutomationScope
import bosca.workops.model.automation.FailureMode
import bosca.workops.model.notification.NotificationChannel
import bosca.workops.model.links.TaskLinkInput
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.model.task.Task
import bosca.workops.model.task.UpdateTaskInput
import bosca.workops.model.workflow.Condition
import bosca.workops.repository.AutomationExecutionLogRepository
import bosca.workops.repository.AutomationLoopGuardRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class AutomationExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val projectId = UUID.random()
    private val profileId = UUID.random()
    private val principalId = UUID.random()
    private val task = Task(
        id = UUID.random(),
        key = "AUTO-2",
        projectId = projectId,
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Automation executor task",
        reporterProfileId = profileId,
        customFieldValues = JsonObject(mapOf("existing" to JsonPrimitive("value"))),
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
        version = 7,
    )

    private val runAsProfile = Profile(
        id = profileId,
        type = ProfileType.GENERIC,
        principal = principalId,
        name = "Automation",
        visibility = ProfileVisibility.USER,
    )

    @Test
    fun `executor performs every implemented action and records success`() = runTest {
        val fixture = fixture()
        val taskTypeId = UUID.random()
        val assigneeProfileId = UUID.random()
        val priorityId = UUID.random()
        val transitionId = UUID.random()
        val createdProjectId = UUID.random()
        val createdTaskTypeId = UUID.random()
        val createdStatusId = UUID.random()
        val createdPriorityId = UUID.random()
        val createdAssigneeId = UUID.random()
        val linkTypeId = UUID.random()
        val targetTaskId = UUID.random()
        val dueDate = OffsetDateTime.now()
        val startDate = OffsetDateTime.now()
        val actions = listOf(
            Action.AddComment("Automated comment"),
            Action.SendWebhook(
                url = "https://example.test/hook",
                headers = mapOf("Authorization" to "secret"),
                bodyTemplate = "payload",
            ),
            Action.SendEmail("team@example.test, ; ", "Subject", "Body"),
            Action.SendSlackMessage("#workops", "Slack body"),
            Action.EditTask(
                buildJsonObject {
                    put("summary", JsonPrimitive("Updated summary"))
                    put("descriptionMarkdown", JsonPrimitive("Updated description"))
                    put("taskTypeId", JsonPrimitive(taskTypeId.toString()))
                    put("assigneeProfileId", JsonPrimitive(assigneeProfileId.toString()))
                    put("clearAssignee", JsonPrimitive(true))
                    put("priorityId", JsonPrimitive(priorityId.toString()))
                    put("dueDate", JsonPrimitive(dueDate.toString()))
                    put("clearDueDate", JsonPrimitive(true))
                    put("startDate", JsonPrimitive(startDate.toString()))
                    put("clearStartDate", JsonPrimitive(true))
                }
            ),
            Action.CreateTask(
                projectId = createdProjectId,
                fields = buildJsonObject {
                    put("summary", JsonPrimitive("Created by automation"))
                    put("taskTypeId", JsonPrimitive(createdTaskTypeId.toString()))
                    put("statusId", JsonPrimitive(createdStatusId.toString()))
                    put("priorityId", JsonPrimitive(createdPriorityId.toString()))
                    put("descriptionMarkdown", JsonPrimitive("Created description"))
                    put("assigneeProfileId", JsonPrimitive(createdAssigneeId.toString()))
                },
            ),
            Action.TransitionTask(transitionId),
            Action.SetFieldValue("risk", "high"),
            Action.AssignTo(assigneeProfileId.toString()),
            Action.LinkTasks(linkTypeId, targetTaskId.toString()),
        )
        val rule = rule(actions)

        assertEquals(
            AutomationOutcome.OK,
            fixture.executor.run(rule, AutomationContext(triggeringTask = task, projectId = projectId)),
        )

        coVerify(exactly = 1) {
            fixture.notificationOutbox.enqueue(
                "AUTOMATION_COMMENT",
                task.id.toString(),
                match { "Automated comment" in it && rule.id.toString() in it },
            )
        }
        coVerify(exactly = 1) {
            fixture.notificationOutbox.enqueue(
                "WEBHOOK",
                "https://example.test/hook",
                match { "Authorization" in it && "secret" in it && "payload" in it },
            )
        }
        coVerify(exactly = 1) {
            fixture.notificationOutbox.enqueueOnce(
                any(),
                NotificationChannelDeliveryServiceImpl.AUTOMATION_EMAIL_EVENT,
                NotificationChannel.EMAIL,
                fixture.emailRecipientId.toString(),
                match { "Subject" in it && "Body" in it },
                null,
                false,
            )
        }
        coVerify(exactly = 1) {
            fixture.notificationOutbox.enqueue("SLACK", "#workops", match { "Slack body" in it })
        }
        coVerify(exactly = 1) {
            fixture.taskService.update(
                task.id,
                match<UpdateTaskInput> {
                    it.summary == "Updated summary" &&
                            it.descriptionMarkdown == "Updated description" &&
                            it.taskTypeId == taskTypeId &&
                            it.assigneeProfileId == assigneeProfileId &&
                            it.clearAssignee &&
                            it.priorityId == priorityId &&
                            it.dueDate == dueDate &&
                            it.clearDueDate &&
                            it.startDate == startDate &&
                            it.clearStartDate &&
                            it.expectedVersion == task.version
                },
                principalId,
                profileId,
            )
        }
        coVerify(exactly = 1) {
            fixture.taskService.create(
                match<CreateTaskInput> {
                    it.projectId == createdProjectId &&
                            it.taskTypeId == createdTaskTypeId &&
                            it.statusId == createdStatusId &&
                            it.priorityId == createdPriorityId &&
                            it.summary == "Created by automation" &&
                            it.descriptionMarkdown == "Created description" &&
                            it.assigneeProfileId == createdAssigneeId
                },
                principalId,
                profileId,
                profileId,
            )
        }
        coVerify(exactly = 1) {
            fixture.taskService.transition(task.id, transitionId, task.version + 1, principalId, profileId)
        }
        coVerify(exactly = 1) {
            fixture.taskService.setCustomFieldValues(
                task.id,
                JsonObject(
                    mapOf(
                        "existing" to JsonPrimitive("value"),
                        "risk" to JsonPrimitive("high"),
                    )
                ),
                task.version + 2,
                principalId,
                profileId,
            )
        }
        coVerify(exactly = 1) {
            fixture.taskService.update(
                task.id,
                match<UpdateTaskInput> {
                    it.summary == null &&
                            it.assigneeProfileId == assigneeProfileId &&
                            it.expectedVersion == task.version + 3
                },
                principalId,
                profileId,
            )
        }
        coVerify(exactly = 1) {
            fixture.taskLinkService.link(
                TaskLinkInput(linkTypeId, task.id, targetTaskId),
                actingPrincipalId = principalId,
            )
        }
        coVerify(exactly = 1) { fixture.loopGuard.bump(rule.id, task.id) }
        coVerify(exactly = 1) {
            fixture.executionLog.add(match { it.outcome == AutomationOutcome.OK.name && it.errorMessage == null })
        }
    }

    @Test
    fun `task-only actions are no-ops for non-task events`() = runTest {
        val fixture = fixture()
        val actions = listOf(
            Action.AddComment("ignored"),
            Action.EditTask(),
            Action.TransitionTask(UUID.random()),
            Action.SetFieldValue("risk", "low"),
            Action.AssignTo(UUID.random().toString()),
            Action.LinkTasks(UUID.random(), UUID.random().toString()),
        )
        val rule = rule(actions)

        assertEquals(
            AutomationOutcome.OK,
            fixture.executor.run(rule, AutomationContext(triggeringTask = null, projectId = projectId)),
        )

        coVerify(exactly = 0) { fixture.notificationOutbox.enqueue(any<String>(), any(), any()) }
        coVerify(exactly = 0) { fixture.taskService.update(any(), any(), any(), any()) }
        coVerify(exactly = 0) { fixture.taskService.transition(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { fixture.taskService.setCustomFieldValues(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { fixture.taskLinkService.link(any(), any()) }
        coVerify(exactly = 0) { fixture.loopGuard.bump(any(), any()) }
    }

    @Test
    fun `executor logs malformed conditions and actions as internal errors`() = runTest {
        val fixture = fixture()
        val badConditionsRule = rule(emptyList()).copy(
            conditions = JsonArray(listOf(JsonPrimitive("not-a-condition"))),
        )
        val badActionsRule = rule(emptyList()).copy(actions = JsonObject(emptyMap()))

        assertEquals(
            AutomationOutcome.INTERNAL_ERROR,
            fixture.executor.run(
                badConditionsRule,
                AutomationContext(triggeringTask = task, projectId = projectId),
            ),
        )
        assertEquals(
            AutomationOutcome.INTERNAL_ERROR,
            fixture.executor.run(badActionsRule, AutomationContext(triggeringTask = null, projectId = projectId)),
        )

        coVerify(exactly = 1) {
            fixture.executionLog.add(
                match {
                    it.ruleId == badConditionsRule.id &&
                            it.outcome == AutomationOutcome.INTERNAL_ERROR.name &&
                            it.errorMessage?.startsWith("decode conditions failed:") == true
                }
            )
        }
        coVerify(exactly = 1) {
            fixture.executionLog.add(
                match {
                    it.ruleId == badActionsRule.id &&
                            it.outcome == AutomationOutcome.INTERNAL_ERROR.name &&
                            it.errorMessage?.startsWith("decode actions failed:") == true
                }
            )
        }
    }

    @Test
    fun `executor honors continue and stop failure modes`() = runTest {
        val fixture = fixture()
        val continueRule = rule(
            actions = listOf(
                Action.CreateTask(projectId, JsonObject(emptyMap())),
                Action.SendEmail("continue@example.test", "continued", "body"),
            ),
            failureMode = FailureMode.CONTINUE,
        )
        val stopRule = rule(
            actions = listOf(
                Action.CreateTask(projectId, JsonObject(emptyMap())),
                Action.SendEmail("stop@example.test", "stopped", "body"),
            ),
            failureMode = FailureMode.STOP_ON_ERROR,
        )

        assertEquals(
            AutomationOutcome.ACTION_FAILED,
            fixture.executor.run(continueRule, AutomationContext(task, projectId)),
        )
        assertEquals(
            AutomationOutcome.ACTION_FAILED,
            fixture.executor.run(stopRule, AutomationContext(task, projectId)),
        )

        coVerify(exactly = 1) {
            fixture.notificationOutbox.enqueueOnce(
                any(),
                NotificationChannelDeliveryServiceImpl.AUTOMATION_EMAIL_EVENT,
                NotificationChannel.EMAIL,
                any(),
                match { "continued" in it },
                null,
                false,
            )
        }
        coVerify(exactly = 0) {
            fixture.notificationOutbox.enqueueOnce(
                any(),
                NotificationChannelDeliveryServiceImpl.AUTOMATION_EMAIL_EVENT,
                NotificationChannel.EMAIL,
                any(),
                match { "stopped" in it },
                null,
                false,
            )
        }
        for (rule in listOf(continueRule, stopRule)) {
            coVerify(exactly = 1) {
                fixture.executionLog.add(
                    match {
                        it.ruleId == rule.id &&
                            it.outcome == AutomationOutcome.ACTION_FAILED.name &&
                            it.errorMessage != null
                    },
                )
            }
        }
        coVerify(exactly = 0) {
            fixture.executionLog.add(match {
                it.ruleId in setOf(continueRule.id, stopRule.id) && it.errorMessage == null
            })
        }
    }

    @Test
    fun `executor maps absent null and invalid optional action fields`() = runTest {
        val fixture = fixture()
        val rule = rule(
            listOf(
                Action.EditTask(JsonObject(emptyMap())),
                Action.EditTask(
                    JsonObject(
                        mapOf(
                            "summary" to JsonNull,
                            "descriptionMarkdown" to JsonNull,
                            "taskTypeId" to JsonNull,
                            "assigneeProfileId" to JsonNull,
                            "clearAssignee" to JsonNull,
                            "priorityId" to JsonNull,
                            "dueDate" to JsonNull,
                            "clearDueDate" to JsonNull,
                            "startDate" to JsonNull,
                            "clearStartDate" to JsonNull,
                        )
                    )
                ),
                Action.EditTask(
                    buildJsonObject {
                        put("clearAssignee", JsonPrimitive("invalid"))
                        put("clearDueDate", JsonPrimitive("invalid"))
                        put("clearStartDate", JsonPrimitive("invalid"))
                    }
                ),
                Action.CreateTask(
                    projectId,
                    buildJsonObject { put("summary", JsonPrimitive("Minimal task")) },
                ),
                Action.CreateTask(
                    projectId,
                    JsonObject(
                        mapOf(
                            "summary" to JsonPrimitive("Null optionals"),
                            "taskTypeId" to JsonNull,
                            "statusId" to JsonNull,
                            "priorityId" to JsonNull,
                            "descriptionMarkdown" to JsonNull,
                            "assigneeProfileId" to JsonNull,
                        )
                    ),
                ),
            )
        )

        assertEquals(
            AutomationOutcome.OK,
            fixture.executor.run(rule, AutomationContext(task, projectId)),
        )

        coVerify(exactly = 3) {
            fixture.taskService.update(
                task.id,
                match<UpdateTaskInput> {
                    it.summary == null &&
                            it.descriptionMarkdown == null &&
                            it.taskTypeId == null &&
                            it.assigneeProfileId == null &&
                            !it.clearAssignee &&
                            it.priorityId == null &&
                            it.dueDate == null &&
                            !it.clearDueDate &&
                            it.startDate == null &&
                            !it.clearStartDate
                },
                principalId,
                profileId,
            )
        }
        coVerify(exactly = 1) {
            fixture.taskService.create(
                match<CreateTaskInput> {
                    it.summary == "Minimal task" &&
                            it.taskTypeId == null &&
                            it.statusId == null &&
                            it.priorityId == null &&
                            it.descriptionMarkdown == null &&
                            it.assigneeProfileId == null
                },
                principalId,
                profileId,
                profileId,
            )
        }
        coVerify(exactly = 1) {
            fixture.taskService.create(
                match<CreateTaskInput> {
                    it.summary == "Null optionals" &&
                            it.taskTypeId == null &&
                            it.statusId == null &&
                            it.priorityId == null &&
                            it.descriptionMarkdown == null &&
                            it.assigneeProfileId == null
                },
                principalId,
                profileId,
                profileId,
            )
        }
    }

    @Test
    fun `executor treats non-array conditions as empty`() = runTest {
        val fixture = fixture()
        val rule = rule(emptyList()).copy(conditions = JsonObject(emptyMap()))

        assertEquals(
            AutomationOutcome.OK,
            fixture.executor.run(rule, AutomationContext(task, projectId)),
        )

        coVerify(exactly = 1) { fixture.loopGuard.bump(rule.id, task.id) }
    }

    @Test
    fun `executor rejects a run-as profile without a principal`() = runTest {
        val fixture = fixture()
        coEvery { fixture.profileService.getById(profileId) } returns runAsProfile.copy(principal = null)
        val rule = rule(listOf(Action.AddComment("must not run")))

        assertEquals(
            AutomationOutcome.ACTION_FAILED,
            fixture.executor.run(rule, AutomationContext(task, projectId)),
        )

        coVerify(exactly = 0) { fixture.loopGuard.bump(rule.id, task.id) }
        coVerify(exactly = 0) { fixture.notificationOutbox.enqueue(any<String>(), any(), any()) }
        coVerify(exactly = 1) {
            fixture.executionLog.add(
                match {
                    it.outcome == AutomationOutcome.ACTION_FAILED.name &&
                        "not linked to a principal" in it.errorMessage.orEmpty()
                }
            )
        }
    }

    @Test
    fun `executor resolves UUID email recipients and rejects an empty recipient set`() = runTest {
        val fixture = fixture()
        val uuidRule = rule(
            listOf(Action.SendEmail(fixture.emailRecipientId.toString(), "UUID recipient", "Body")),
        )

        assertEquals(
            AutomationOutcome.OK,
            fixture.executor.run(uuidRule, AutomationContext(task, projectId)),
        )
        coVerify(exactly = 1) { fixture.profileService.getById(fixture.emailRecipientId) }

        coEvery { fixture.profileService.getProfilesByEmail("nobody@example.test") } returns emptyList()
        val emptyRule = rule(
            listOf(Action.SendEmail("nobody@example.test", "Nobody", "Body")),
        )
        assertEquals(
            AutomationOutcome.ACTION_FAILED,
            fixture.executor.run(emptyRule, AutomationContext(task, projectId)),
        )
    }

    @Test
    fun `executor records only the first failure while continuing through later failures`() = runTest {
        val fixture = fixture()
        val rule = rule(
            actions = listOf(
                Action.AssignTo("not-a-uuid"),
                Action.LinkTasks(UUID.random(), "also-not-a-uuid"),
            ),
            failureMode = FailureMode.CONTINUE,
        )

        assertEquals(
            AutomationOutcome.ACTION_FAILED,
            fixture.executor.run(rule, AutomationContext(task, projectId)),
        )
        coVerify(exactly = 1) {
            fixture.executionLog.add(match { it.ruleId == rule.id && it.errorMessage != null })
        }
    }

    @Test
    fun `executor names a message-less run-as lookup failure by exception type`() = runTest {
        val fixture = fixture()
        coEvery { fixture.profileService.getById(profileId) } throws IllegalStateException()
        val rule = rule(listOf(Action.AddComment("never runs")))

        assertEquals(
            AutomationOutcome.ACTION_FAILED,
            fixture.executor.run(rule, AutomationContext(task, projectId)),
        )
        coVerify(exactly = 1) {
            fixture.executionLog.add(match { it.errorMessage == "IllegalStateException" })
        }
    }

    @Test
    fun `executor falls back to the exception type when an action failure has no message`() = runTest {
        val fixture = fixture()
        coEvery {
            fixture.notificationOutbox.enqueueOnce(any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException()
        val rule = rule(listOf(Action.SendEmail("fail@example.test", "subject", "body")))

        assertEquals(
            AutomationOutcome.ACTION_FAILED,
            fixture.executor.run(rule, AutomationContext(task, projectId)),
        )

        coVerify(exactly = 1) {
            fixture.executionLog.add(
                match {
                    it.outcome == AutomationOutcome.ACTION_FAILED.name &&
                            it.errorMessage == "IllegalStateException"
                }
            )
        }
    }

    @Test
    fun `every pending action reports not implemented`() = runTest {
        val fixture = fixture()
        val pendingActions = listOf(
            Action.CreateSubtask(),
            Action.Sleep(1),
            Action.Branch(Condition.Always),
            Action.ForEach("tasks"),
            Action.RunScript("script"),
            Action.Lookup("query", "result"),
            Action.AssignToOnCallRotation(UUID.random()),
        )

        pendingActions.forEach { action ->
            val rule = rule(listOf(action))
            assertEquals(
                AutomationOutcome.NOT_IMPLEMENTED,
                fixture.executor.run(rule, AutomationContext(task, projectId)),
                action::class.simpleName,
            )
        }

        coVerify(exactly = pendingActions.size) {
            fixture.executionLog.add(
                match { it.outcome == AutomationOutcome.NOT_IMPLEMENTED.name && it.errorMessage != null }
            )
        }
    }

    private fun fixture(): Fixture {
        val executionLog = mockk<AutomationExecutionLogRepository>(relaxed = true)
        val loopGuard = mockk<AutomationLoopGuardRepository>(relaxed = true)
        val taskService = mockk<TaskService>(relaxed = true)
        val taskLinkService = mockk<TaskLinkService>(relaxed = true)
        val notificationOutbox = mockk<NotificationOutboxService>(relaxed = true)
        val profileService = mockk<ProfileService>()
        val emailRecipientId = UUID.random()
        val emailRecipient = runAsProfile.copy(id = emailRecipientId, principal = UUID.random(), name = "Recipient")
        coEvery { loopGuard.currentCount(any(), any()) } returns null
        coEvery { loopGuard.bump(any(), any()) } returns 1
        coEvery { profileService.getById(profileId) } returns runAsProfile
        coEvery { profileService.getById(emailRecipientId) } returns emailRecipient
        coEvery { profileService.getProfilesByEmail(any()) } returns listOf(emailRecipient)
        coEvery { taskService.update(any(), any(), any(), any()) } answers {
            task.copy(version = secondArg<UpdateTaskInput>().expectedVersion + 1)
        }
        coEvery { taskService.transition(any(), any(), any(), any(), any()) } answers {
            task.copy(version = thirdArg<Long>() + 1)
        }
        coEvery { taskService.setCustomFieldValues(any(), any(), any(), any(), any()) } answers {
            task.copy(version = thirdArg<Long>() + 1, customFieldValues = secondArg())
        }
        return Fixture(
            executionLog = executionLog,
            loopGuard = loopGuard,
            taskService = taskService,
            taskLinkService = taskLinkService,
            notificationOutbox = notificationOutbox,
            profileService = profileService,
            emailRecipientId = emailRecipientId,
            executor = AutomationExecutorImpl(
                executionLog,
                loopGuard,
                taskService,
                taskLinkService,
                notificationOutbox,
                profileService,
                json,
            ),
        )
    }

    private fun rule(
        actions: List<Action>,
        failureMode: FailureMode = FailureMode.STOP_ON_ERROR,
    ): AutomationRule = AutomationRule(
        id = UUID.random(),
        scope = AutomationScope.GLOBAL,
        name = "Executor rule",
        trigger = JsonObject(emptyMap()),
        conditions = json.encodeToJsonElement(ListSerializer(Condition.serializer()), emptyList()),
        actions = json.encodeToJsonElement(ListSerializer(Action.serializer()), actions),
        runAsProfileId = profileId,
        failureMode = failureMode,
    )

    private data class Fixture(
        val executionLog: AutomationExecutionLogRepository,
        val loopGuard: AutomationLoopGuardRepository,
        val taskService: TaskService,
        val taskLinkService: TaskLinkService,
        val notificationOutbox: NotificationOutboxService,
        val profileService: ProfileService,
        val emailRecipientId: UUID,
        val executor: AutomationExecutor,
    )
}
