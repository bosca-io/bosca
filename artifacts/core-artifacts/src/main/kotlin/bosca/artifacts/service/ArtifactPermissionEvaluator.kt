package bosca.artifacts.service

import bosca.artifacts.model.ArtifactAction
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory

/**
 * Evaluates whether a principal has access to a specific artifact for a requested action.
 *
 * Registry clients (docker/maven/npm CLIs) authenticate with a scoped API token (`bsk_` prefix)
 * whose `artifacts:` scopes grant the operation. JWT/session principals — a Bosca service or a
 * signed-in user — are authorized instead against the namespace under the standard platform
 * permission model (public flag, group grants, admin/service-account overrides) via
 * [ArtifactNamespacePermissionEvaluator], exactly as bosca-server evaluates any `PermissibleEntity`.
 *
 * Scopes follow the format: `artifacts:type:namespace/repository:version-or-tag:action`
 *
 * Each segment can be a specific value, a glob pattern (e.g., `v3.*`), or `*` for wildcard.
 * Actions can be comma-separated (e.g., `pull,push`).
 *
 * **Important:** Namespace and repository names must not contain colons, as colons are
 * used as the scope delimiter. Names are validated during creation to prevent this.
 *
 * Public namespaces allow anonymous pull access without a token.
 */
class ArtifactPermissionEvaluator(
    private val namespaceEvaluator: ArtifactNamespacePermissionEvaluator,
) {

    /**
     * Checks whether the authenticated principal has permission to perform the given
     * action on the specified artifact coordinates.
     */
    suspend fun evaluate(
        auth: AuthenticationContext?,
        type: String,
        namespace: String,
        repository: String,
        versionOrTag: String?,
        action: ArtifactAction,
        isPublicNamespace: Boolean = false,
    ): Boolean {
        val principal = auth?.principal()

        // Anonymous access: only allowed for pulls on public namespaces
        if (principal == null) {
            log.warn("Anonymous access to $type:$namespace/$repository")
            return action == ArtifactAction.PULL && isPublicNamespace
        }

        // JWT/session principals aren't scoped API tokens — authorize them against the namespace under
        // the standard platform permission model (public flag, group grants, admin/service-account
        // overrides), exactly as bosca-server does for any PermissibleEntity. Scoped bsk_ tokens keep
        // the scope-based path below.
        if (principal !is ScopedAuthenticatedPrincipal) {
            if (action == ArtifactAction.PULL && isPublicNamespace) return true
            val ns = namespaceEvaluator.service.getNamespaceByName(namespace) ?: return false
            return namespaceEvaluator.isAllowed(auth, ns, action.toPermissionAction())
        }

        // API tokens must carry at least one artifacts: scope for registry access.
        // Tokens without scopes (null) are unrestricted for general API use but
        // must still have explicit artifact scopes for registry operations.
        val scopes = principal.scopes
        if (scopes == null) {
            log.error("Token has no scopes: ${principal.id}")
            return false
        }
        if (scopes.none { it.startsWith("artifacts:") }) {
            log.error("Token has no artifacts: scope: ${principal.id}")
            return false
        }

        // Check whether any scope on the token grants the requested action.
        // Broad scopes (artifacts:pull, artifacts:push, artifacts:admin) grant
        // access to all artifact types, namespaces, and versions for their action.
        val hasScope = scopes.any { scope ->
            matchesBroadScope(scope, action) ||
                matchesScope(scope, type, namespace, repository, versionOrTag, action)
        }
        if (!hasScope) log.warn("Principal ${principal.id} lacks required scope for $action on $type:$namespace/$repository")
        return hasScope
    }

    /** Maps a registry [ArtifactAction] onto the platform [PermissionAction] used for namespace grants. */
    private fun ArtifactAction.toPermissionAction(): PermissionAction = when (this) {
        ArtifactAction.PULL -> PermissionAction.VIEW
        ArtifactAction.PUSH -> PermissionAction.EDIT
        ArtifactAction.ADMIN -> PermissionAction.MANAGE
    }

    /**
     * Throws [SecurityException] if the principal lacks the required permission.
     */
    suspend fun verify(
        auth: AuthenticationContext?,
        type: String,
        namespace: String,
        repository: String,
        versionOrTag: String?,
        action: ArtifactAction,
        isPublicNamespace: Boolean = false,
    ) {
        if (!evaluate(auth, type, namespace, repository, versionOrTag, action, isPublicNamespace)) {
            throw SecurityException("Insufficient permissions for $action on $type:$namespace/$repository")
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(ArtifactPermissionEvaluator::class.java)

        /** LRU cache of compiled glob-to-regex patterns to avoid repeated compilation on every request. */
        private val globCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .build<String, Regex>()

        /**
         * Checks whether a broad scope like `artifacts:pull`, `artifacts:push`, or
         * `artifacts:admin` grants the requested action. These scopes apply to all
         * artifact types, namespaces, and versions.
         */
        internal fun matchesBroadScope(scope: String, action: ArtifactAction): Boolean {
            return when (scope) {
                "artifacts:admin" -> true
                "artifacts:push" -> action == ArtifactAction.PUSH || action == ArtifactAction.PULL
                "artifacts:pull" -> action == ArtifactAction.PULL
                else -> false
            }
        }

        internal fun matchesScope(
            scope: String,
            type: String,
            namespace: String,
            repository: String,
            versionOrTag: String?,
            action: ArtifactAction,
        ): Boolean {
            if (!scope.startsWith("artifacts:")) return false

            val parts = scope.removePrefix("artifacts:").split(":")
            if (parts.size != 4) return false

            val scopeType = parts[0]
            val scopePath = parts[1]
            val scopeVersion = parts[2]
            val scopeActions = parts[3]

            if (scopeType != "*" && scopeType != type) return false
            if (!pathMatches(scopePath, namespace, repository)) return false

            val versionToCheck = versionOrTag ?: "*"
            if (!matchesGlob(scopeVersion, versionToCheck)) return false

            val allowedActions = scopeActions.split(",").map { it.trim() }
            return action.value in allowedActions || "admin" in allowedActions
        }

        private fun pathMatches(scopePath: String, namespace: String, repository: String): Boolean {
            if (scopePath == "*") return true

            val parts = scopePath.split("/", limit = 2)
            if (parts.size == 1) {
                return matchesGlob(parts[0], namespace)
            }

            val scopeNamespace = parts[0]
            val scopeRepo = parts[1]

            if (!matchesGlob(scopeNamespace, namespace)) return false
            return matchesGlob(scopeRepo, repository)
        }

        internal fun matchesGlob(pattern: String, value: String): Boolean {
            if (pattern == "*") return true
            if (!pattern.contains('*')) return pattern == value

            val compiled = globCache.get(pattern) { p ->
                val regexStr = buildString {
                    append("^")
                    for (char in p) {
                        when (char) {
                            '*' -> append(".*")
                            '.' -> append("\\.")
                            '(' -> append("\\(")
                            ')' -> append("\\)")
                            '[' -> append("\\[")
                            ']' -> append("\\]")
                            '{' -> append("\\{")
                            '}' -> append("\\}")
                            '+' -> append("\\+")
                            '?' -> append("\\?")
                            '^' -> append("\\^")
                            '$' -> append("\\$")
                            '|' -> append("\\|")
                            '\\' -> append("\\\\")
                            else -> append(char)
                        }
                    }
                    append("$")
                }
                Regex(regexStr)
            }
            return compiled.matches(value)
        }

        /**
         * Validates that a scope string is a well-formed artifact scope.
         * Delegates to [ApiTokenScopes.isValidArtifactScope] to avoid duplicating validation logic.
         */
        fun isValidArtifactScope(scope: String): Boolean {
            if (!scope.startsWith("artifacts:")) return false
            return ApiTokenScopes.isValidArtifactScope(scope)
        }
    }
}
