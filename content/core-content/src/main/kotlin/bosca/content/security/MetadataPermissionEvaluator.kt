package bosca.content.security

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

class MetadataPermissionEvaluator(
    override val service: MetadataService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator
) : PermissionEvaluator<Metadata, UUID>()