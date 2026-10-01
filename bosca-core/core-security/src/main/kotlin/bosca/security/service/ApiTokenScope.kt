package bosca.security.service

/**
 * Describes a permission scope that can be assigned to an API token to restrict
 * its capabilities below the principal's full permission set.
 *
 * Scopes follow the `resource:action` convention (e.g., `content:view`). When an
 * API token carries scopes, only the intersection of the principal's group-based
 * permissions and the token's scopes is granted.
 *
 * @param name the scope identifier used in token attributes (e.g., `"content:view"`)
 * @param description a human-readable explanation of what the scope permits
 */
data class ApiTokenScope(
    val name: String,
    val description: String
)

/**
 * Registry of all available API token scopes.
 *
 * This serves as the single source of truth for valid scope values. The [all] list
 * must be manually maintained — when adding a new scope property, it must also be
 * added to [all] for it to be recognized by validation and exposed via the
 * GraphQL `availableScopes` query.
 */
object ApiTokenScopes {

    // Content
    val CONTENT_VIEW = ApiTokenScope("content:view", "Read content and metadata, including change subscriptions")
    val CONTENT_EDIT = ApiTokenScope("content:edit", "Create and modify content")
    val CONTENT_DELETE = ApiTokenScope("content:delete", "Delete content")
    val CONTENT_MANAGE = ApiTokenScope("content:manage", "Manage content workflows and permissions")

    // Collections
    val COLLECTIONS_VIEW = ApiTokenScope("collections:view", "Read collections, including change subscriptions")
    val COLLECTIONS_EDIT = ApiTokenScope("collections:edit", "Create and modify collections")
    val COLLECTIONS_DELETE = ApiTokenScope("collections:delete", "Delete collections")
    val COLLECTIONS_MANAGE = ApiTokenScope("collections:manage", "Manage collection permissions")

    // Profiles
    val PROFILES_READ = ApiTokenScope("profiles:read", "Read profiles and profile attributes")
    val PROFILES_EDIT = ApiTokenScope("profiles:edit", "Create and modify profiles")
    val PROFILES_DELETE = ApiTokenScope("profiles:delete", "Delete profiles")
    val PROFILES_MANAGE = ApiTokenScope("profiles:manage", "Manage profile types and configurations")

    // Analytics
    val ANALYTICS_VIEW = ApiTokenScope("analytics:view", "Read analytics data and dashboards")
    val ANALYTICS_EXECUTE = ApiTokenScope("analytics:execute", "Execute analytics queries")
    val ANALYTICS_MANAGE = ApiTokenScope("analytics:manage", "Manage analytics configurations and queries")

    // Security
    val SECURITY_VIEW = ApiTokenScope("security:view", "Read principals and groups")
    val SECURITY_MANAGE = ApiTokenScope("security:manage", "Manage principals, groups, and permissions")

    // Jobs & Scheduler
    val JOBS_VIEW = ApiTokenScope("jobs:view", "View job definitions and history")
    val JOBS_EXECUTE = ApiTokenScope("jobs:execute", "Trigger and manage jobs")

    // Storage
    val STORAGE_READ = ApiTokenScope("storage:read", "Download files via signed URLs")
    val STORAGE_WRITE = ApiTokenScope("storage:write", "Upload files via signed URLs")

    // Forms
    val FORMS_READ = ApiTokenScope("forms:read", "Read form schemas and submissions")
    val FORMS_EDIT = ApiTokenScope("forms:edit", "Create and modify form schemas")
    val FORMS_SUBMIT = ApiTokenScope("forms:submit", "Submit responses to form schemas")
    val FORMS_MANAGE = ApiTokenScope("forms:manage", "Manage form configurations")

    // Community
    val COMMUNITY_READ = ApiTokenScope("community:read", "Read community groups and members")
    val COMMUNITY_EDIT = ApiTokenScope("community:edit", "Create and modify community groups")
    val COMMUNITY_MANAGE = ApiTokenScope("community:manage", "Manage community configurations")

    // Search
    val SEARCH_READ = ApiTokenScope("search:read", "Execute search queries")
    val SEARCH_MANAGE = ApiTokenScope("search:manage", "Manage search configurations and indexes")

    // Segments & Campaigns
    val SEGMENTS_READ = ApiTokenScope("segments:read", "Read segments and membership")
    val SEGMENTS_EDIT = ApiTokenScope("segments:edit", "Create and modify segments")
    val SEGMENTS_MANAGE = ApiTokenScope("segments:manage", "Manage segment evaluation and campaigns")

    // Messages
    val MESSAGES_READ = ApiTokenScope("messages:read", "Read messages and channels")
    val MESSAGES_EDIT = ApiTokenScope("messages:edit", "Send and modify messages")

    // Scripts
    val SCRIPTS_VIEW = ApiTokenScope("scripts:view", "Read scripts")
    val SCRIPTS_EXECUTE = ApiTokenScope("scripts:execute", "Execute scripts")
    val SCRIPTS_MANAGE = ApiTokenScope("scripts:manage", "Manage script definitions")

    // Git
    val GIT_READ = ApiTokenScope("git:read", "Clone and fetch git repositories")
    val GIT_WRITE = ApiTokenScope("git:write", "Push to git repositories")
    val GIT_MANAGE = ApiTokenScope("git:manage", "Manage git repository settings, webhooks, and branch protection")

    // CI/CD Pipelines
    val CI_READ = ApiTokenScope("ci:read", "View pipeline agents, runs, jobs, and logs")
    val CI_EDIT = ApiTokenScope("ci:edit", "Write CI-owned data back to repositories (e.g., update source refs after a pipeline run)")
    val CI_EXECUTE = ApiTokenScope("ci:execute", "Agent operations: heartbeat, claim jobs, update status, append logs")
    val CI_MANAGE = ApiTokenScope("ci:manage", "Register and deregister agents, configure orchestrators, manage pipeline secrets")

    // Artifacts (base scopes for broad access; fine-grained scopes use artifacts:<type>:<path>:<version>:<action> format)
    val ARTIFACTS_PULL = ApiTokenScope("artifacts:pull", "Pull/download artifacts from all registries")
    val ARTIFACTS_PUSH = ApiTokenScope("artifacts:push", "Push/publish artifacts to all registries")
    val ARTIFACTS_ADMIN = ApiTokenScope("artifacts:admin", "Manage artifacts, tags, and namespaces across all registries")

    // Gateway — proxied upstreams (Trino, internal HTTP services). Read = safe methods (GET/HEAD/OPTIONS); write = mutating methods.
    val GATEWAY_READ = ApiTokenScope("gateway:read", "Send read requests (GET, HEAD, OPTIONS) through gateways to upstream services")
    val GATEWAY_WRITE = ApiTokenScope("gateway:write", "Send write requests (POST, PUT, PATCH, DELETE) through gateways to upstream services")

    // MCP
    val MCP_EXECUTE = ApiTokenScope("mcp:execute", "Manage execute")

    /**
     * All registered scopes. When adding a new scope property above, it must also
     * be added to this list.
     */
    val all: List<ApiTokenScope> = listOf(
        CONTENT_VIEW, CONTENT_EDIT, CONTENT_DELETE, CONTENT_MANAGE,
        COLLECTIONS_VIEW, COLLECTIONS_EDIT, COLLECTIONS_DELETE, COLLECTIONS_MANAGE,
        PROFILES_READ, PROFILES_EDIT, PROFILES_DELETE, PROFILES_MANAGE,
        ANALYTICS_VIEW, ANALYTICS_EXECUTE, ANALYTICS_MANAGE,
        SECURITY_VIEW, SECURITY_MANAGE,
        JOBS_VIEW, JOBS_EXECUTE,
        STORAGE_READ, STORAGE_WRITE,
        FORMS_READ, FORMS_EDIT, FORMS_SUBMIT, FORMS_MANAGE,
        COMMUNITY_READ, COMMUNITY_EDIT, COMMUNITY_MANAGE,
        SEARCH_READ, SEARCH_MANAGE,
        SEGMENTS_READ, SEGMENTS_EDIT, SEGMENTS_MANAGE,
        MESSAGES_READ, MESSAGES_EDIT,
        SCRIPTS_VIEW, SCRIPTS_EXECUTE, SCRIPTS_MANAGE,
        GIT_READ, GIT_WRITE, GIT_MANAGE,
        CI_READ, CI_EDIT, CI_EXECUTE, CI_MANAGE,
        ARTIFACTS_PULL, ARTIFACTS_PUSH, ARTIFACTS_ADMIN,
        GATEWAY_READ, GATEWAY_WRITE,
        MCP_EXECUTE
    )

    private val validNames: Set<String> = all.mapTo(mutableSetOf()) { it.name }

    /**
     * Returns `true` if the given scope name is a recognized scope value.
     *
     * In addition to the statically registered scopes, this accepts dynamic artifact
     * scopes following the `artifacts:<type>:<namespace/repo>:<version>:<actions>` format
     * used for fine-grained access control over specific artifact registries.
     */
    fun isValid(scope: String): Boolean {
        if (scope in validNames) return true
        // Accept dynamic artifact scopes (e.g., artifacts:docker:acme/api:v3.*:pull)
        if (scope.startsWith("artifacts:") && scope.count { it == ':' } == 4) {
            return isValidArtifactScope(scope)
        }
        return false
    }

    /**
     * Valid artifact type names for fine-grained artifact scopes.
     * Must match the values of the `ArtifactType` enum in the `core-artifacts` module.
     */
    private val validArtifactTypes = setOf("docker", "helm", "maven", "npm", "raw", "ml")

    /** Valid actions for artifact scopes. */
    private val validArtifactActions = setOf("pull", "push", "admin")

    /**
     * Validates the structure of a fine-grained artifact scope string.
     */
    fun isValidArtifactScope(scope: String): Boolean {
        val parts = scope.removePrefix("artifacts:").split(":")
        if (parts.size != 4) return false
        val type = parts[0]
        val path = parts[1]
        val version = parts[2]
        val actions = parts[3]
        if (type != "*" && type !in validArtifactTypes) return false
        if (path.isBlank()) return false
        if (version.isBlank()) return false
        val actionList = actions.split(",").map { it.trim() }
        return actionList.all { it in validArtifactActions }
    }
}
