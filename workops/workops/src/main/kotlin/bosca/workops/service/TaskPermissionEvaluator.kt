package bosca.workops.service

import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.task.Task

/**
 * Standard Bosca permission evaluator for [Portfolio] entities.
 * Uses per-entity permission rows checked against group membership,
 * exactly like [bosca.content.security.MetadataPermissionEvaluator].
 */
class PortfolioPermissionEvaluator(
    override val service: PortfolioService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<Portfolio, UUID>()

/**
 * Standard Bosca permission evaluator for [Program] entities.
 */
class ProgramPermissionEvaluator(
    override val service: ProgramService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<Program, UUID>()

/**
 * Standard Bosca permission evaluator for [Project] entities.
 */
class ProjectPermissionEvaluator(
    override val service: ProjectService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<Project, UUID>()

/**
 * Standard Bosca permission evaluator for [Task] entities.
 */
class TaskPermissionEvaluator(
    override val service: TaskService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<Task, UUID>()

class SpecPermissionEvaluator(
    override val service: SpecService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<bosca.workops.model.spec.Spec, UUID>()

/**
 * Standard Bosca permission evaluator for [bosca.workops.model.environment.Environment] entities:
 * environment-targeting actions (approve, deploy, rollback) check the environment's
 * own permission rows, with the parent Program as fallback.
 */
class EnvironmentPermissionEvaluator(
    override val service: EnvironmentService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<bosca.workops.model.environment.Environment, UUID>()

class RequirementPermissionEvaluator(
    override val service: RequirementService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<bosca.workops.model.requirement.Requirement, UUID>()
