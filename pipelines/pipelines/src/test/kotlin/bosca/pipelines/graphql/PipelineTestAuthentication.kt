package bosca.pipelines.graphql

import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

internal fun pipelineAdministrator(): AuthenticationContext = object : AuthenticationContext(null, null) {
    override fun principal() = AuthenticatedPrincipal(Principal(UUID.random(), anonymous = false),
        listOf(Group(UUID.random(), "administrators", "Administrators", GroupType.SYSTEM)))
}
