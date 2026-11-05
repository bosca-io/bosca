package bosca.workops.service

import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.workops.model.project.Program

/**
 * Standard Bosca permission evaluator for [Program] entities.
 */
class ProgramPermissionEvaluator(
    override val service: ProgramService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<Program, UUID>()
