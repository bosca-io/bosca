package bosca.pipelines.graphql

import bosca.security.service.ApiTokenScope
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Keeps pipeline authoring and inspection administrator-only with a domain-specific token scope. */
internal fun GroupEvaluator.verifyPipelineAdministrator(authentication: AuthenticationContext, scope: ApiTokenScope) {
    verifyHasScope(authentication, scope.name)
    if (authentication.principal()?.hasGroup("administrators") != true) throwUnauthorized()
}
