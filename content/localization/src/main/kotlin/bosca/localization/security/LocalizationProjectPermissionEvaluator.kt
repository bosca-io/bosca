@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.security

import bosca.localization.model.LocalizationProject
import bosca.localization.service.LocalizationService
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Authorizes access to a [LocalizationProject] using Bosca's standard
 * [PermissionEvaluator] machinery. Project-scoped permissions are stored in
 * `localization.project_permissions` and surfaced through [LocalizationService]
 * (which implements `PermissionService<LocalizationProject, UUID>`).
 *
 * Permission tiers used by the mutation controller:
 * - VIEW: read projects, strings, translations, progress
 * - EDIT: create/modify translations, transition to IN_REVIEW
 * - MANAGE: approve/reject/publish, configure sync, manage membership
 *
 * Global `administrators` and `sa` groups bypass project-level checks
 * (inherited from [PermissionEvaluator]).
 */
class LocalizationProjectPermissionEvaluator(
    override val service: LocalizationService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator
) : PermissionEvaluator<LocalizationProject, UUID>()
