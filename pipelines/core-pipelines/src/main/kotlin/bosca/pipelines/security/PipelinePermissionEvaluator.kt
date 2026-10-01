package bosca.pipelines.security

import bosca.pipelines.model.Pipeline
import bosca.pipelines.service.PipelineService
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

/**
 * Evaluates whether a caller may access or execute a [Pipeline], applying the standard permission
 * model (public flag, group-based grants, admin/service-account overrides) — what gates the
 * `/api/v1/p/{key}` REST endpoint for non-public pipelines. Mirrors `ScriptPermissionEvaluator`.
 */
class PipelinePermissionEvaluator(
    override val service: PipelineService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<Pipeline, UUID>()
