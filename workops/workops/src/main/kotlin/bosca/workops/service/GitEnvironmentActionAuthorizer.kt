package bosca.workops.service

import bosca.git.service.EnvironmentActionAuthorizer
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

/**
 * The WorkOps-backed [EnvironmentActionAuthorizer]: resolves the git repository to its
 * owning program(s) through the project↔repository links, finds each program's environment by KEY,
 * and evaluates the caller against the environment's own permission rows via
 * [EnvironmentPermissionEvaluator] — standard Bosca entity-permission semantics, parent Program as
 * fallback.
 *
 * Fail-closed on both unknowns: a repository no program links, or a key no linked program declares,
 * throws — an environment nobody can evaluate must not accept actions. When several programs link
 * the repository, the caller must be allowed on EVERY matching environment.
 */
class GitEnvironmentActionAuthorizer(
    private val projectRepositories: ProjectRepositoryService,
    private val projectService: ProjectService,
    private val environmentService: EnvironmentService,
    private val environmentPermissionEvaluator: EnvironmentPermissionEvaluator,
) : EnvironmentActionAuthorizer {

    override suspend fun verifyAllowed(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        environmentKey: String,
        action: PermissionAction,
    ) {
        val programIds = projectRepositories.listByRepository(repositoryId)
            .mapNotNull { link -> projectService.getById(link.projectId)?.programId }
            .distinct()
        check(programIds.isNotEmpty()) {
            "Repository $repositoryId is not linked to any WorkOps project — " +
                "environment '$environmentKey' cannot be evaluated"
        }
        val environments = programIds.mapNotNull { environmentService.getByProgramAndKey(it, environmentKey) }
        check(environments.isNotEmpty()) {
            "Environment '$environmentKey' does not exist in any WorkOps program linked to this repository — " +
                "sync the release pipeline before acting on it"
        }
        for (environment in environments) {
            environmentPermissionEvaluator.verifyAllowed(authentication, environment, action)
        }
    }
}
