package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.automation.Action
import bosca.workops.model.automation.AutomationRule
import bosca.workops.model.automation.AutomationScope
import bosca.workops.model.automation.FailureMode
import bosca.workops.model.automation.Trigger
import bosca.workops.model.workflow.Condition
import bosca.workops.repository.AutomationRuleInsertParams
import bosca.workops.repository.AutomationRuleRepository
import bosca.workops.repository.AutomationRuleUpdateParams
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AutomationRuleServiceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `rule service maps inputs and delegates lifecycle operations`() = runTest {
        val repository = mockk<AutomationRuleRepository>()
        val service = AutomationRuleServiceImpl(repository, json)
        val ruleId = UUID.random()
        val scopeId = UUID.random()
        val runAsProfileId = UUID.random()
        val input = AutomationRuleInput(
            scope = AutomationScope.PROGRAM,
            scopeId = scopeId,
            name = "Notify on priority changes",
            description = "Keeps the owning team informed",
            enabled = true,
            trigger = Trigger.TaskUpdated(fieldKeys = listOf("priorityId")),
            conditions = listOf(Condition.Always),
            actions = listOf(Action.AddComment("Priority changed")),
            runAsProfileId = runAsProfileId,
            failureMode = FailureMode.CONTINUE,
            executionLogRetentionDays = 45,
            maxFiresPerTaskPerHour = 8,
        )
        val rule = AutomationRule(
            id = ruleId,
            scope = input.scope,
            scopeId = input.scopeId,
            name = input.name,
            description = input.description,
            enabled = input.enabled,
            trigger = json.encodeToJsonElement(Trigger.serializer(), input.trigger),
            conditions = json.encodeToJsonElement(ListSerializer(Condition.serializer()), input.conditions),
            actions = json.encodeToJsonElement(ListSerializer(Action.serializer()), input.actions),
            runAsProfileId = input.runAsProfileId,
            failureMode = input.failureMode,
            executionLogRetentionDays = input.executionLogRetentionDays,
            maxFiresPerTaskPerHour = input.maxFiresPerTaskPerHour,
        )
        val updatedRule = rule.copy(name = "Updated rule", version = 1)
        val insert = slot<AutomationRuleInsertParams>()
        val update = slot<AutomationRuleUpdateParams>()
        val missingId = UUID.random()

        coEvery { repository.getById(ruleId) } returns rule
        coEvery { repository.listInScope(AutomationScope.PROGRAM.name, scopeId) } returns listOf(rule)
        coEvery { repository.listEnabled(AutomationScope.PROGRAM.name, scopeId) } returns listOf(rule)
        coEvery { repository.add(capture(insert)) } returns rule
        coEvery { repository.update(capture(update)) } returns updatedRule
        coEvery {
            repository.update(match { it.id == missingId && it.expectedVersion == 2L })
        } returns null
        coEvery { repository.delete(ruleId) } just Runs

        assertEquals(rule, service.getById(ruleId))
        assertEquals(listOf(rule), service.listInScope(AutomationScope.PROGRAM, scopeId))
        assertEquals(listOf(rule), service.listEnabledForScope(AutomationScope.PROGRAM, scopeId))
        assertEquals(rule, service.create(input))
        assertEquals(updatedRule, service.update(ruleId, input, expectedVersion = 0))
        assertFailsWith<WorkOpsNotFoundException> {
            service.update(missingId, input, expectedVersion = 2)
        }
        service.delete(ruleId)

        assertEquals(AutomationScope.PROGRAM.name, insert.captured.scope)
        assertEquals(scopeId, insert.captured.scopeId)
        assertEquals(input.name, insert.captured.name)
        assertEquals(input.description, insert.captured.description)
        assertEquals(input.enabled, insert.captured.enabled)
        assertEquals(input.trigger, json.decodeFromString(Trigger.serializer(), insert.captured.trigger))
        assertEquals(
            input.conditions,
            json.decodeFromString(ListSerializer(Condition.serializer()), insert.captured.conditions),
        )
        assertEquals(
            input.actions,
            json.decodeFromString(ListSerializer(Action.serializer()), insert.captured.actions),
        )
        assertEquals(runAsProfileId, insert.captured.runAsProfileId)
        assertEquals(FailureMode.CONTINUE.name, insert.captured.failureMode)
        assertEquals(45, insert.captured.executionLogRetentionDays)
        assertEquals(8, insert.captured.maxFiresPerTaskPerHour)

        assertEquals(ruleId, update.captured.id)
        assertEquals(input.name, update.captured.name)
        assertEquals(input.description, update.captured.description)
        assertEquals(input.enabled, update.captured.enabled)
        assertEquals(input.trigger, json.decodeFromString(Trigger.serializer(), update.captured.trigger))
        assertEquals(
            input.conditions,
            json.decodeFromString(ListSerializer(Condition.serializer()), update.captured.conditions),
        )
        assertEquals(
            input.actions,
            json.decodeFromString(ListSerializer(Action.serializer()), update.captured.actions),
        )
        assertEquals(runAsProfileId, update.captured.runAsProfileId)
        assertEquals(FailureMode.CONTINUE.name, update.captured.failureMode)
        assertEquals(45, update.captured.executionLogRetentionDays)
        assertEquals(8, update.captured.maxFiresPerTaskPerHour)
        assertEquals(0, update.captured.expectedVersion)

        coVerify(exactly = 1) { repository.delete(ruleId) }
    }
}
