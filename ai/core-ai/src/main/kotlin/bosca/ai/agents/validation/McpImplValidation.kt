package bosca.ai.agents.validation

import bosca.ai.agents.model.AgentResourceInput
import bosca.ai.agents.model.AgentToolInput

/**
 * App-layer validation of the mutually-exclusive implementation variants on
 * [AgentToolInput] and [AgentResourceInput].
 *
 * This mirrors (and extends to the full impl-field set) the Git-side check in
 * `AgentRepoValidator` ("tool '...' sets both mcp_server and script — at most one is
 * allowed"). It runs on every write path (Studio/GraphQL and Git sync, since Git sync
 * builds the same `*Input` types), so an ambiguous combination is rejected before it can
 * be persisted.
 *
 * All failures throw [IllegalArgumentException] with a message naming the entity key and
 * every variant field that was set.
 */
object McpImplValidation {

    /**
     * Validates an [AgentTool] implementation. An AgentTool has **at most one** impl
     * variant — zero set means a code-backed tool (resolved at dispatch time by matching
     * the tool's `key` to a registered code primitive).
     *
     * Variants: `scriptId`, `mcpServerId`, `graphqlOperation`, `promptId`+`modelId`, `agentId`.
     */
    fun validate(input: AgentToolInput) {
        val variants = buildList {
            if (input.scriptId != null) add("scriptId")
            if (input.mcpServerId != null) add("mcpServerId")
            if (input.graphqlOperation != null) add("graphqlOperation")
            if (input.promptId != null || input.modelId != null) add("promptId+modelId")
            if (input.agentId != null) add("agentId")
        }
        require(variants.size <= 1) {
            "AgentTool '${input.key}' sets multiple implementation variants " +
                "(${variants.joinToString(", ")}) — at most one is allowed " +
                "(or none, for a code-backed tool)."
        }
        require(input.promptId == null && input.modelId == null || input.promptId != null && input.modelId != null) {
            val missing = if (input.promptId == null) "promptId" else "modelId"
            "AgentTool '${input.key}' uses the prompt+model variant but is missing $missing — both are required."
        }
        require(input.graphqlInputTransform == null && input.graphqlOutputTransform == null || input.graphqlOperation != null) {
            "AgentTool '${input.key}' sets a GraphQL transform without graphqlOperation — transforms require graphqlOperation."
        }
    }

    /**
     * Validates an [AgentResource] implementation. An AgentResource has **exactly one**
     * impl variant — unlike a tool there is no code-backed form, since a resource must
     * produce content from somewhere.
     *
     * Variants: `staticText`, `metadataId`, `documentMetadataId`(+`documentVersion`),
     * `contentMetadataId`, `scriptId`, `graphqlOperation`(+transforms).
     */
    fun validate(input: AgentResourceInput) {
        val variants = buildList {
            if (input.staticText != null) add("staticText")
            if (input.metadataId != null) add("metadataId")
            if (input.documentMetadataId != null) add("documentMetadataId")
            if (input.contentMetadataId != null) add("contentMetadataId")
            if (input.scriptId != null) add("scriptId")
            if (input.graphqlOperation != null) add("graphqlOperation")
        }
        require(variants.size == 1) {
            if (variants.isEmpty()) {
                "AgentResource '${input.key}' sets no implementation variant — exactly one of " +
                    "staticText, metadataId, documentMetadataId, contentMetadataId, scriptId, " +
                    "graphqlOperation is required."
            } else {
                "AgentResource '${input.key}' sets multiple implementation variants " +
                    "(${variants.joinToString(", ")}) — exactly one is allowed."
            }
        }
        require(input.documentVersion == null || input.documentMetadataId != null) {
            "AgentResource '${input.key}' sets documentVersion without documentMetadataId."
        }
        require(input.graphqlInputTransform == null && input.graphqlOutputTransform == null || input.graphqlOperation != null) {
            "AgentResource '${input.key}' sets a GraphQL transform without graphqlOperation — transforms require graphqlOperation."
        }
    }
}
