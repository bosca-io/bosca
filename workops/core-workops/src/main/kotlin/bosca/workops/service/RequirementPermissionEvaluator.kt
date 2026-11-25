package bosca.workops.service

import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.workops.model.requirement.Requirement

/** Shared requirement authorization through the owning service and current group permissions. */
class RequirementPermissionEvaluator(
    override val service: RequirementService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<Requirement, UUID>()
