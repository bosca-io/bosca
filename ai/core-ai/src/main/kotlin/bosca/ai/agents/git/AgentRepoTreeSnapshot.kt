package bosca.ai.agents.git

/**
 * In-memory snapshot of every file under the AGENT_PROJECT directories at a single
 * commit, plus the sets of keys for entities referenced from the snapshot that live only
 * in PostgreSQL. Pure data — both the pre-receive hook and the post-write pull path
 * construct one of these and hand it to [AgentRepoValidator].
 */
data class AgentRepoTreeSnapshot(
    val mcpServers: List<McpServerFile> = emptyList(),
    val tools: List<ToolFile> = emptyList(),
    val prompts: List<PromptFile> = emptyList(),
    val agents: List<AgentFile> = emptyList(),
    val resources: List<ResourceFile> = emptyList(),
    val knownModelKeys: Set<String> = emptySet(),
    val knownPromptKeys: Set<String> = emptySet(),
    val knownToolKeys: Set<String> = emptySet(),
    val knownMcpServerKeys: Set<String> = emptySet(),
    val knownSubAgentKeys: Set<String> = emptySet(),
    val knownScriptKeys: Set<String> = emptySet()
)
