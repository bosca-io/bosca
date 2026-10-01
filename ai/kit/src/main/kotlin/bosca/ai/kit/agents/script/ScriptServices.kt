package bosca.ai.kit.agents.script

import bosca.ai.agents.service.AgentService
import bosca.ai.agents.service.AgentToolService
import bosca.scripting.engine.Engine
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService

/**
 * The platform services the [ScriptAgent]'s tools need, bundled so the script capability threads a
 * single dependency through the planner/action chain rather than five. ([AgentService]/[AgentToolService]
 * are used only to auto-register a TOOL script as an agent tool — a best-effort step that degrades
 * gracefully when the script agent isn't present.)
 */
class ScriptServices(
    val scriptService: ScriptService,
    val scriptExecutionService: ScriptExecutionService,
    val engine: Engine,
    val agentToolService: AgentToolService,
    val agentService: AgentService,
)
