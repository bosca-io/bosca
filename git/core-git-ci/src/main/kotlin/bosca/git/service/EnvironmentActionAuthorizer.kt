package bosca.git.service

import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Authorizes environment-targeting actions: approving or rejecting an
 * environment-bound job, deploying, promoting, rolling back. Repository EXECUTE only says "may run
 * releases"; touching a specific environment additionally requires permission on the WorkOps
 * Environment the job's environment KEY links to — evaluated with standard Bosca entity-permission
 * semantics, which live in another module, so this is an SPI.
 *
 * Fail-closed by contract: implementations throw when the environment key has no linked WorkOps
 * environment — an action against an environment nobody can evaluate must not proceed — and the
 * call sites treat a MISSING implementation the same way.
 */
interface EnvironmentActionAuthorizer : Service {

    /**
     * Throws (with the evaluator's standard authorization error) unless [authentication] holds
     * [action] on the environment [environmentKey] linked to [repositoryId]'s program(s).
     */
    suspend fun verifyAllowed(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        environmentKey: String,
        action: PermissionAction,
    )
}
