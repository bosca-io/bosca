package bosca.ai.kit.agents.script

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * The script sub-agent's **structured** result: a human-facing [message] summarizing what it did
 * (created/edited/ran/listed scripts), produced after its tool loop runs.
 */
@Serializable
@LLMDescription("A summary of the scripting operation performed and its outcome.")
data class ScriptResponse(
    @property:LLMDescription("A concise, user-facing summary of what was done and the result (mention key names, ids, or errors).")
    val message: String,
)
