package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.workops.model.automation.Action
import bosca.workops.model.automation.AutomationExecutionLog
import bosca.workops.model.automation.AutomationRule
import bosca.workops.model.automation.AutomationScope
import bosca.workops.model.automation.FailureMode
import bosca.workops.model.automation.Trigger
import bosca.workops.model.workflow.Condition
import bosca.workops.service.AutomationExecutionLogService
import bosca.workops.service.AutomationRuleInput
import bosca.workops.service.AutomationRuleService
import bosca.workops.service.PortfolioPermissionEvaluator
import bosca.workops.service.PortfolioService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@kotlinx.serialization.Serializable
data class AutomationRuleDocumentInput(
    val scope: String,
    @kotlinx.serialization.Contextual
    val scopeId: UUID? = null,
    val name: String,
    val description: String? = null,
    val enabled: Boolean,
    @kotlinx.serialization.Contextual
    val trigger: JsonElement,
    @kotlinx.serialization.Contextual
    val conditions: JsonElement,
    @kotlinx.serialization.Contextual
    val actions: JsonElement,
    @kotlinx.serialization.Contextual
    val runAsProfileId: UUID,
    val failureMode: String,
    val executionLogRetentionDays: Int,
    val maxFiresPerTaskPerHour: Int,
)

@TypeController(type = "WorkOpsAutomationRule")
class AutomationRuleTypeController : GraphQLController<AutomationRule> {
    @Field fun id(r: AutomationRule) = r.id
    @Field fun scope(r: AutomationRule): String = r.scope.name
    @Field fun scopeId(r: AutomationRule) = r.scopeId
    @Field fun name(r: AutomationRule) = r.name
    @Field fun description(r: AutomationRule) = r.description
    @Field fun enabled(r: AutomationRule) = r.enabled
    @Field fun trigger(r: AutomationRule): JsonElement = r.trigger
    @Field fun conditions(r: AutomationRule): JsonElement = r.conditions
    @Field fun actions(r: AutomationRule): JsonElement = r.actions
    @Field fun runAsProfileId(r: AutomationRule) = r.runAsProfileId
    @Field fun failureMode(r: AutomationRule): String = r.failureMode.name
    @Field fun executionLogRetentionDays(r: AutomationRule) = r.executionLogRetentionDays
    @Field fun maxFiresPerTaskPerHour(r: AutomationRule) = r.maxFiresPerTaskPerHour
    @Field fun version(r: AutomationRule) = r.version
}

@TypeController(type = "WorkOpsAutomationExecutionLog")
class AutomationExecutionLogTypeController : GraphQLController<AutomationExecutionLog> {
    @Field fun id(log: AutomationExecutionLog) = log.id
    @Field fun ruleId(log: AutomationExecutionLog) = log.ruleId
    @Field fun taskId(log: AutomationExecutionLog) = log.taskId
    @Field fun outcome(log: AutomationExecutionLog): String = log.outcome.name
    @Field fun startedAt(log: AutomationExecutionLog) = log.startedAt
    @Field fun finishedAt(log: AutomationExecutionLog) = log.finishedAt
    @Field fun durationMs(log: AutomationExecutionLog) = log.durationMs
    @Field fun errorMessage(log: AutomationExecutionLog) = log.errorMessage
}

object WorkOpsAutomationQuery

@TypeController
class AutomationQueryController(
    private val ruleService: AutomationRuleService,
    private val logService: AutomationExecutionLogService,
    private val projectService: ProjectService,
    private val programService: ProgramService,
    private val portfolioService: PortfolioService,
    private val projectPermissions: ProjectPermissionEvaluator,
    private val programPermissions: ProgramPermissionEvaluator,
    private val portfolioPermissions: PortfolioPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<WorkOpsAutomationQuery> {

    private suspend fun verifyScopeView(
        authentication: AuthenticationContext,
        scope: AutomationScope,
        scopeId: UUID?,
    ) {
        when (scope) {
            AutomationScope.GLOBAL -> groupEvaluator.verifyHasAdminGroup(authentication)
            AutomationScope.PORTFOLIO -> {
                val portfolio = portfolioService.getById(scopeId ?: error("PORTFOLIO scope requires scopeId"))
                    ?: error("Portfolio $scopeId not found")
                portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.VIEW)
            }
            AutomationScope.PROGRAM -> {
                val program = programService.getById(scopeId ?: error("PROGRAM scope requires scopeId"))
                    ?: error("Program $scopeId not found")
                programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
            }
            AutomationScope.PROJECT -> {
                val project = projectService.getById(scopeId ?: error("PROJECT scope requires scopeId"))
                    ?: error("Project $scopeId not found")
                projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
            }
        }
    }

    @Field
    suspend fun rules(authentication: AuthenticationContext, scope: String, scopeId: UUID? = null): List<AutomationRule> {
        val parsed = AutomationScope.valueOf(scope)
        verifyScopeView(authentication, parsed, scopeId)
        return ruleService.listInScope(parsed, scopeId)
    }

    @Field
    suspend fun rule(authentication: AuthenticationContext, id: UUID): AutomationRule? {
        val rule = ruleService.getById(id) ?: return null
        verifyScopeView(authentication, rule.scope, rule.scopeId)
        return rule
    }

    @Field
    suspend fun executionLogs(
        authentication: AuthenticationContext,
        ruleId: UUID,
        offset: Long,
        limit: Int,
    ): List<AutomationExecutionLog> {
        val rule = ruleService.getById(ruleId) ?: error("AutomationRule $ruleId not found")
        verifyScopeView(authentication, rule.scope, rule.scopeId)
        return logService.listForRule(ruleId, offset, limit)
    }
}

object WorkOpsAutomationMutation

@TypeController
class AutomationMutationController(
    private val ruleService: AutomationRuleService,
    private val projectService: ProjectService,
    private val programService: ProgramService,
    private val portfolioService: PortfolioService,
    private val projectPermissions: ProjectPermissionEvaluator,
    private val programPermissions: ProgramPermissionEvaluator,
    private val portfolioPermissions: PortfolioPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
    private val json: Json,
) : GraphQLController<WorkOpsAutomationMutation> {

    /**
     * Verifies MANAGE permission on the entity identified by [scope] and [scopeId].
     * Global-scoped rules require admin group membership since they have no parent entity.
     */
    private suspend fun verifyScopeManage(
        authentication: AuthenticationContext,
        scope: AutomationScope,
        scopeId: UUID?,
    ) {
        when (scope) {
            AutomationScope.GLOBAL -> groupEvaluator.verifyHasAdminGroup(authentication)
            AutomationScope.PORTFOLIO -> {
                val portfolio = portfolioService.getById(scopeId ?: error("PORTFOLIO scope requires scopeId"))
                    ?: error("Portfolio $scopeId not found")
                portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE)
            }
            AutomationScope.PROGRAM -> {
                val program = programService.getById(scopeId ?: error("PROGRAM scope requires scopeId"))
                    ?: error("Program $scopeId not found")
                programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
            }
            AutomationScope.PROJECT -> {
                val project = projectService.getById(scopeId ?: error("PROJECT scope requires scopeId"))
                    ?: error("Project $scopeId not found")
                projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
            }
        }
    }

    private fun decodeInput(input: AutomationRuleDocumentInput): AutomationRuleInput {
        val trigger = json.decodeFromJsonElement(Trigger.serializer(), input.trigger)
        val conditions = json.decodeFromJsonElement(ListSerializer(Condition.serializer()), input.conditions)
        val actions = json.decodeFromJsonElement(ListSerializer(Action.serializer()), input.actions)
        return AutomationRuleInput(
            scope = AutomationScope.valueOf(input.scope),
            scopeId = input.scopeId,
            name = input.name,
            description = input.description,
            enabled = input.enabled,
            trigger = trigger,
            conditions = conditions,
            actions = actions,
            runAsProfileId = input.runAsProfileId,
            failureMode = FailureMode.valueOf(input.failureMode),
            executionLogRetentionDays = input.executionLogRetentionDays,
            maxFiresPerTaskPerHour = input.maxFiresPerTaskPerHour,
        )
    }

    @Field
    suspend fun create(authentication: AuthenticationContext, input: AutomationRuleDocumentInput): AutomationRule {
        val decoded = decodeInput(input)
        verifyScopeManage(authentication, decoded.scope, decoded.scopeId)
        return ruleService.create(decoded)
    }

    @Field
    suspend fun update(
        authentication: AuthenticationContext,
        id: UUID,
        input: AutomationRuleDocumentInput,
        expectedVersion: Long,
    ): AutomationRule {
        val existing = ruleService.getById(id)
            ?: error("AutomationRule $id not found")
        verifyScopeManage(authentication, existing.scope, existing.scopeId)
        val decoded = decodeInput(input)
        if (decoded.scope != existing.scope || decoded.scopeId != existing.scopeId) {
            error("AutomationRule scope cannot be changed via update")
        }
        return ruleService.update(id, decoded, expectedVersion)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        val existing = ruleService.getById(id)
            ?: error("AutomationRule $id not found")
        verifyScopeManage(authentication, existing.scope, existing.scopeId)
        ruleService.delete(id)
        return true
    }

    @Field
    suspend fun setEnabled(
        authentication: AuthenticationContext,
        id: UUID,
        enabled: Boolean,
        expectedVersion: Long,
    ): AutomationRule {
        val existing = ruleService.getById(id)
            ?: error("AutomationRule $id not found")
        verifyScopeManage(authentication, existing.scope, existing.scopeId)
        // Update with the same content but flipped enabled flag.
        val trigger = json.decodeFromJsonElement(Trigger.serializer(), existing.trigger)
        val conditions = json.decodeFromJsonElement(ListSerializer(Condition.serializer()), existing.conditions)
        val actions = json.decodeFromJsonElement(ListSerializer(Action.serializer()), existing.actions)
        return ruleService.update(
            id,
            AutomationRuleInput(
                scope = existing.scope,
                scopeId = existing.scopeId,
                name = existing.name,
                description = existing.description,
                enabled = enabled,
                trigger = trigger,
                conditions = conditions,
                actions = actions,
                runAsProfileId = existing.runAsProfileId,
                failureMode = existing.failureMode,
                executionLogRetentionDays = existing.executionLogRetentionDays,
                maxFiresPerTaskPerHour = existing.maxFiresPerTaskPerHour,
            ),
            expectedVersion,
        )
    }
}
