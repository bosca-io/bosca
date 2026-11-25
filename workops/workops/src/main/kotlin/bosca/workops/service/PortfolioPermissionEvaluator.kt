package bosca.workops.service

import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.workops.model.project.Portfolio

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
