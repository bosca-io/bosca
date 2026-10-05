package bosca.ai.kit.agents.writer

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * The writer's structured decision for one turn: does it have everything it needs, or must it ask for
 * Scripture first? This carries ONLY the control-flow choice — never the document body.
 *
 * The body is a large free-form HTML blob, and embedding it in a JSON string field is exactly what made
 * the writer fail: the model's structured response was truncated mid-`html` (the closing quote never
 * arrived), so the whole turn was lost to a JSON parse error. The body therefore travels as a PLAIN
 * assistant message instead (see [WriterAgent]); this decision stays small enough to always serialize.
 */
@Serializable
@LLMDescription("Whether the writer needs Scripture it was not given. Never put the document here.")
data class WriterDecision(
    @property:LLMDescription("True if you need Scripture you were NOT given below; then fill references.")
    val needsScripture: Boolean = false,
    @property:LLMDescription("The Scripture references you need, e.g. [\"John 3:16\"] — only when needsScripture is true.")
    val references: List<String> = emptyList(),
)
