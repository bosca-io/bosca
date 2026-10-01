package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.serialization.UUID

object AdminMutation

/** Admin-only account administration mutations (`security.admin.*`). Nothing here is automatic or self-serve. */
@TypeController
class AdminMutationController(
    private val securityService: SecurityService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<AdminMutation> {

    /**
     * Merges [duplicateId] into [survivorId]: the survivor keeps everything, the duplicate is retired.
     * Use [AdminQueryController.duplicateAccounts] to find candidates first.
     */
    @Field
    suspend fun mergePrincipals(
        authentication: AuthenticationContext,
        survivorId: UUID,
        duplicateId: UUID,
    ): Principal {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return securityService.mergePrincipals(survivorId, duplicateId)
    }

    /**
     * Marks a principal deleted — the reversible staging step before [deletePrincipal]. Revokes the
     * principal's sessions and blocks it from authenticating until [restorePrincipal].
     */
    @Field
    suspend fun markPrincipalDeleted(
        authentication: AuthenticationContext,
        id: UUID,
    ): Principal {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return securityService.markPrincipalDeleted(id)
    }

    /** Reverses [markPrincipalDeleted], re-enabling the account. */
    @Field
    suspend fun restorePrincipal(
        authentication: AuthenticationContext,
        id: UUID,
    ): Principal {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return securityService.restorePrincipal(id)
    }

    /**
     * Permanently deletes a principal and everything the database cascades from it — credentials,
     * group memberships, emails, tokens, and the principal's linked profiles. The principal MUST
     * already be soft-deleted (via [markPrincipalDeleted]); this two-stage requirement guards against
     * accidental one-click destruction.
     */
    @Field
    suspend fun deletePrincipal(
        authentication: AuthenticationContext,
        id: UUID,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val principal = securityService.getPrincipalById(id) ?: throw SecurityException("principal not found")
        if (principal.deletedAt == null) {
            throw SecurityException("principal must be marked deleted before it can be permanently deleted")
        }
        securityService.deletePrincipal(id)
        return true
    }
}
