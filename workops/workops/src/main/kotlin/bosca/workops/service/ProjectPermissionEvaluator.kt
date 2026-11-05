package bosca.workops.service

import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.workops.model.project.Project

/**
 * Standard Bosca permission evaluator for [Project] entities.
 */
class ProjectPermissionEvaluator(
    override val service: ProjectService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<Project, UUID>()
