package bosca.workops.service

import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

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
