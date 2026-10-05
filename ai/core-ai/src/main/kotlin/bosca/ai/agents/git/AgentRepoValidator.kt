package bosca.ai.agents.git

import bosca.ai.agents.git.AgentRepoLayout.AGENTS_DIR
import bosca.ai.agents.git.AgentRepoLayout.MCP_SERVERS_DIR
import bosca.ai.agents.git.AgentRepoLayout.MD_EXTENSION
import bosca.ai.agents.git.AgentRepoLayout.PROMPTS_DIR
import bosca.ai.agents.git.AgentRepoLayout.RESOURCES_DIR
import bosca.ai.agents.git.AgentRepoLayout.TOOLS_DIR

/**
 * Validates an [AgentRepoTreeSnapshot] for the structural invariants the AGENT_PROJECT
 * sync flow depends on. Pure — no IO. Shared by the pre-receive hook and the post-write
 * pull path so the two cannot diverge.
 */
interface AgentRepoValidator {
    fun validate(snapshot: AgentRepoTreeSnapshot): ValidationResult
}

/** Outcome of an [AgentRepoValidator.validate] call. Either [Ok] or [Errors] with at least one entry. */
sealed class ValidationResult {
    /** The snapshot satisfies every structural rule. */
    data object Ok : ValidationResult()

    /** The snapshot violates one or more rules. Every detected error is included. */
    data class Errors(val errors: List<RepoValidationError>) : ValidationResult()
}

/** A single rule violation, attributed to the file [path] where it was found. */
data class RepoValidationError(val path: String, val message: String)

/**
 * Default implementation. Performs all checks in a single pass and surfaces every
 * detected error — the caller (pre-receive hook, pull-side sync) sees the full picture
 * rather than having to fix-and-retry one error at a time.
 */
class AgentRepoValidatorImpl : AgentRepoValidator {

    override fun validate(snapshot: AgentRepoTreeSnapshot): ValidationResult {
        val errors = mutableListOf<RepoValidationError>()

        checkFilenameMatchesKey(snapshot.mcpServers, MCP_SERVERS_DIR, errors) { it.path to it.key }
        checkFilenameMatchesKey(snapshot.tools, TOOLS_DIR, errors) { it.path to it.key }
        checkFilenameMatchesKey(snapshot.prompts, PROMPTS_DIR, errors) { it.path to it.key }
        checkFilenameMatchesKey(snapshot.agents, AGENTS_DIR, errors) { it.path to it.key }
        checkFilenameMatchesKey(snapshot.resources, RESOURCES_DIR, errors) { it.path to it.key }

        checkDuplicateKeys(snapshot.mcpServers, errors) { it.path to it.key }
        checkDuplicateKeys(snapshot.tools, errors) { it.path to it.key }
        checkDuplicateKeys(snapshot.prompts, errors) { it.path to it.key }
        checkDuplicateKeys(snapshot.agents, errors) { it.path to it.key }
        checkDuplicateKeys(snapshot.resources, errors) { it.path to it.key }

        for (tool in snapshot.tools) {
            val variants = buildList {
                if (tool.scriptKey != null) add("script")
                if (tool.mcpServerKey != null) add("mcp_server")
                if (tool.graphqlOperation != null) add("graphql_operation")
                if (tool.promptKey != null || tool.modelKey != null) add("prompt+model")
                if (tool.agentKey != null) add("agent")
            }
            if (variants.size > 1) {
                errors += RepoValidationError(
                    tool.path,
                    "tool '${tool.key}' sets multiple implementation variants (${variants.joinToString(", ")}) — at most one is allowed"
                )
            }
            if ((tool.promptKey != null) != (tool.modelKey != null)) {
                errors += RepoValidationError(
                    tool.path,
                    "tool '${tool.key}' uses the prompt+model variant but is missing ${if (tool.promptKey == null) "prompt" else "model"} — both are required"
                )
            }
        }

        // A resource must produce content from exactly one source — unlike a tool there is no
        // code-backed (zero-variant) form. Metadata slug existence is NOT checked here; slugs
        // resolve to UUIDs at sync time (a dangling slug surfaces there), since the set of all
        // metadata slugs is unbounded and cannot be cheaply pre-listed like the key indexes.
        for (resource in snapshot.resources) {
            val variants = buildList {
                if (resource.staticText != null) add("static_text")
                if (resource.metadataSlug != null) add("metadata")
                if (resource.documentMetadataSlug != null) add("document_metadata")
                if (resource.contentMetadataSlug != null) add("content_metadata")
                if (resource.scriptKey != null) add("script")
                if (resource.graphqlOperation != null) add("graphql_operation")
            }
            when {
                variants.isEmpty() -> errors += RepoValidationError(
                    resource.path,
                    "resource '${resource.key}' sets no implementation variant — exactly one of " +
                        "static_text, metadata, document_metadata, content_metadata, script, graphql_operation is required"
                )
                variants.size > 1 -> errors += RepoValidationError(
                    resource.path,
                    "resource '${resource.key}' sets multiple implementation variants (${variants.joinToString(", ")}) — exactly one is allowed"
                )
            }
            if (resource.documentVersion != null && resource.documentMetadataSlug == null) {
                errors += RepoValidationError(
                    resource.path,
                    "resource '${resource.key}' sets document_version without document_metadata"
                )
            }
            if ((resource.graphqlInputTransform != null || resource.graphqlOutputTransform != null) && resource.graphqlOperation == null) {
                errors += RepoValidationError(
                    resource.path,
                    "resource '${resource.key}' sets a graphql transform without graphql_operation — transforms require graphql_operation"
                )
            }
            if (resource.scriptKey != null && resource.scriptKey !in snapshot.knownScriptKeys) {
                errors += RepoValidationError(
                    resource.path,
                    "resource '${resource.key}' references unknown script '${resource.scriptKey}'"
                )
            }
        }

        val resolvableMcpServerKeys = snapshot.mcpServers.mapTo(mutableSetOf()) { it.key } + snapshot.knownMcpServerKeys
        val resolvableToolKeys = snapshot.tools.mapTo(mutableSetOf()) { it.key } + snapshot.knownToolKeys
        val resolvablePromptKeys = snapshot.prompts.mapTo(mutableSetOf()) { it.key } + snapshot.knownPromptKeys
        val resolvableSubAgentKeys = snapshot.agents.mapTo(mutableSetOf()) { it.key } + snapshot.knownSubAgentKeys

        for (tool in snapshot.tools) {
            if (tool.mcpServerKey != null && tool.mcpServerKey !in resolvableMcpServerKeys) {
                errors += RepoValidationError(
                    tool.path,
                    "tool '${tool.key}' references unknown mcp_server '${tool.mcpServerKey}'"
                )
            }
            if (tool.scriptKey != null && tool.scriptKey !in snapshot.knownScriptKeys) {
                errors += RepoValidationError(
                    tool.path,
                    "tool '${tool.key}' references unknown script '${tool.scriptKey}'"
                )
            }
            if (tool.modelKey != null && tool.modelKey !in snapshot.knownModelKeys) {
                errors += RepoValidationError(
                    tool.path,
                    "tool '${tool.key}' references unknown model '${tool.modelKey}'"
                )
            }
            if (tool.promptKey != null && tool.promptKey !in resolvablePromptKeys) {
                errors += RepoValidationError(
                    tool.path,
                    "tool '${tool.key}' references unknown prompt '${tool.promptKey}'"
                )
            }
            if (tool.agentKey != null && tool.agentKey !in resolvableSubAgentKeys) {
                errors += RepoValidationError(
                    tool.path,
                    "tool '${tool.key}' references unknown agent '${tool.agentKey}'"
                )
            }
        }

        for (agent in snapshot.agents) {
            if (agent.modelKey !in snapshot.knownModelKeys) {
                errors += RepoValidationError(
                    agent.path,
                    "agent '${agent.key}' references unknown model '${agent.modelKey}'"
                )
            }
            if (agent.promptKey !in resolvablePromptKeys) {
                errors += RepoValidationError(
                    agent.path,
                    "agent '${agent.key}' references unknown prompt '${agent.promptKey}'"
                )
            }
            for (subKey in agent.subAgentKeys) {
                if (subKey !in resolvableSubAgentKeys) {
                    errors += RepoValidationError(
                        agent.path,
                        "agent '${agent.key}' references unknown sub_agent '$subKey'"
                    )
                }
            }
            for (toolKey in agent.toolKeys) {
                if (toolKey !in resolvableToolKeys) {
                    errors += RepoValidationError(
                        agent.path,
                        "agent '${agent.key}' references unknown tool '$toolKey'"
                    )
                }
            }
        }

        // Only detects cycles fully contained within the in-tree agents. Cross-DB cycles
        // would require the full sub_agent graph from PostgreSQL; v1 leaves those for
        // runtime stack-overflow detection.
        detectSubAgentCycles(snapshot.agents, errors)

        return if (errors.isEmpty()) ValidationResult.Ok else ValidationResult.Errors(errors)
    }

    private fun <T> checkFilenameMatchesKey(
        files: List<T>,
        directory: String,
        errors: MutableList<RepoValidationError>,
        extract: (T) -> Pair<String, String>
    ) {
        for (file in files) {
            val (path, key) = extract(file)
            val expectedKey = extractKeyFromPath(path, directory)
            when {
                expectedKey == null -> errors += RepoValidationError(
                    path,
                    "invalid path — expected '$directory/<key>$MD_EXTENSION'"
                )
                expectedKey != key -> errors += RepoValidationError(
                    path,
                    "filename key '$expectedKey' does not match frontmatter key '$key'"
                )
            }
        }
    }

    private fun extractKeyFromPath(path: String, directory: String): String? {
        val prefix = "$directory/"
        if (!path.startsWith(prefix) || !path.endsWith(MD_EXTENSION)) return null
        val inner = path.substring(prefix.length, path.length - MD_EXTENSION.length)
        if (inner.isEmpty() || inner.contains('/')) return null
        return inner
    }

    private fun <T> checkDuplicateKeys(
        files: List<T>,
        errors: MutableList<RepoValidationError>,
        extract: (T) -> Pair<String, String>
    ) {
        val seen = mutableMapOf<String, String>()
        for (file in files) {
            val (path, key) = extract(file)
            val existing = seen[key]
            if (existing != null) {
                errors += RepoValidationError(
                    path,
                    "duplicate key '$key' — already defined at '$existing'"
                )
            } else {
                seen[key] = path
            }
        }
    }

    private fun detectSubAgentCycles(agents: List<AgentFile>, errors: MutableList<RepoValidationError>) {
        val byKey = agents.associateBy { it.key }
        val color = mutableMapOf<String, Int>().apply {
            agents.forEach { put(it.key, WHITE) }
        }
        val pathStack = mutableListOf<String>()
        val reported = mutableSetOf<String>()

        fun visit(key: String) {
            val state = color[key] ?: return  // not in-tree — terminal
            when (state) {
                BLACK -> return
                GRAY -> {
                    val cycleStart = pathStack.indexOf(key)
                    if (cycleStart >= 0) {
                        val cycle = pathStack.subList(cycleStart, pathStack.size) + key
                        // Undirected dedup is safe: sub_agentKeys cannot contain duplicates per source,
                        // so two distinct directed cycles cannot share the same vertex set.
                        val cycleId = cycle.toSet().sorted().joinToString(",")
                        if (cycleId !in reported) {
                            reported += cycleId
                            val path = byKey[key]?.path ?: "$AGENTS_DIR/$key$MD_EXTENSION"
                            errors += RepoValidationError(
                                path,
                                "sub_agent cycle detected: ${cycle.joinToString(" -> ")}"
                            )
                        }
                    }
                    return
                }
                WHITE -> {
                    color[key] = GRAY
                    pathStack.add(key)
                    byKey[key]?.subAgentKeys?.forEach { visit(it) }
                    pathStack.removeAt(pathStack.lastIndex)
                    color[key] = BLACK
                }
            }
        }

        for (agent in agents) {
            if (color[agent.key] == WHITE) visit(agent.key)
        }
    }

    private companion object {
        const val WHITE = 0
        const val GRAY = 1
        const val BLACK = 2
    }
}
