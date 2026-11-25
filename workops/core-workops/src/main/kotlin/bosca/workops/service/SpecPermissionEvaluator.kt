package bosca.workops.service

import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.workops.model.spec.Spec

/** Shared specification authorization through the owning service and current group permissions. */
class SpecPermissionEvaluator(
    override val service: SpecService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<Spec, UUID>()
