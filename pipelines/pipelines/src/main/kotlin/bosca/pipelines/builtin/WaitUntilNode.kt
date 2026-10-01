package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.seconds

/**
 * Timer: parks a durable run until an **absolute** target timestamp, then resumes it with the
 * inbound value passed through unchanged. It computes
 * `delay = target - now` and reuses the Delay node's durable scheduled-resume mechanism
 * ([durableTimerSuspend]) — so, like Delay, the wait is a single durable queue row plus a Postgres
 * checkpoint and holds no worker.
 *
 * The target is resolved, in order:
 *  1. [untilField] — a dot-path into the inbound JSON (e.g. `order.shipBy`), so an upstream node
 *     (JSONata, a fetch) can compute *when* to continue from the run's own data;
 *  2. [until] — a static ISO-8601 setting;
 *  3. otherwise a **bare inbound timestamp string** (the inbound value is itself the ISO-8601 time).
 *
 * The resolved value must be ISO-8601 (`OffsetDateTime.parse`, e.g. `2026-07-01T09:00:00Z`). A
 * durable run with no resolvable target fails with a clear error rather than parking forever. A
 * target already in the past is an immediate pass-through (no parking). Dry and non-durable runs pass
 * the value straight through.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Wait Until",
    description = "Pauses the run until an absolute timestamp (from a setting, an inbound field, or the inbound value), then continues unchanged — durable, holds no worker.",
    group = "Core",
    subgroup = "Gates & Timing",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Value",
            description = "The value passed through after the wait; when 'until field' is set it must be an object carrying the ISO-8601 target at that dot-path, otherwise it may itself be the ISO-8601 target string.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "untilField", control = SettingControl.TEXT, label = "Timestamp field (dot-path)", mono = true,
            placeholder = "e.g. order.shipBy",
        ),
        SettingSlot(
            name = "until", control = SettingControl.TEXT, label = "Or a fixed timestamp (ISO-8601)", mono = true,
            placeholder = "2026-07-01T09:00:00Z",
            description = "Pauses until an absolute time. The field is read from the inbound value (takes precedence); otherwise the fixed timestamp is used, or the inbound value itself if it is a timestamp string. A time already in the past continues immediately.",
        ),
    ],
)
@Serializable
@SerialName("waitUntil")
class WaitUntilNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** Dot-path into the inbound JSON to read the ISO-8601 target from (takes precedence over [until]). */
    val untilField: String = "",
    /** Static ISO-8601 target timestamp, used when [untilField] is blank. */
    val until: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    /**
     * Durable + resolvable future target → park and schedule a self-resume for that moment. A past
     * target, dry runs, and non-durable runs fall back to the synchronous pass-through in [execute].
     */
    override suspend fun run(context: PipelineContext, inputs: NodeInputs): NodeResult {
        val runId = context.runId
        if (context.dryRun || runId == null) return NodeResult.Output(execute(context, inputs))

        val target = resolveTarget(context, inputs.first)
        val delaySeconds = target.toEpochSecond() - OffsetDateTime.now().toEpochSecond()
        if (delaySeconds <= 0) return NodeResult.Output(execute(context, inputs)) // already due
        return durableTimerSuspend(context, id, inputs.first, delaySeconds.seconds)
    }

    /** Resolve and parse the ISO-8601 target; fail the run (no silent default) when none is available. */
    private fun resolveTarget(context: PipelineContext, inbound: PipelineValue?): OffsetDateTime {
        val iso = resolveTargetIso(context, inbound)
            ?: error(
                "Wait Until node '${name.ifBlank { id }}': no target timestamp — set `until`, set `untilField` " +
                    "to a field in the inbound value, or feed an ISO-8601 timestamp string"
            )
        return runCatching { OffsetDateTime.parse(iso) }.getOrElse {
            error("Wait Until node '${name.ifBlank { id }}': cannot parse target timestamp '$iso' as ISO-8601")
        }
    }

    private fun resolveTargetIso(context: PipelineContext, inbound: PipelineValue?): String? {
        if (untilField.isNotBlank()) {
            val element = inbound?.encode(context.json) ?: return null
            return (navigate(element, untilField) as? JsonPrimitive)?.contentOrNull
        }
        if (until.isNotBlank()) return until
        // A bare inbound timestamp string (e.g. the output of an upstream JSONata extracting a date).
        return (inbound?.encode(context.json) as? JsonPrimitive)?.contentOrNull
    }

    /** Walk a dot-path (`a.b.c`) into a JSON object; null if any segment is missing or not an object. */
    private fun navigate(element: JsonElement, path: String): JsonElement? {
        var current: JsonElement? = element
        for (key in path.split('.')) {
            current = (current as? JsonObject)?.get(key) ?: return null
        }
        return current
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        if (context.dryRun) {
            context.trace?.recordAction(id, buildJsonObject {
                put("action", "waitUntil")
                put("until", until)
                put("untilField", untilField)
            })
        }
        // A timer is a pass-through gate, not a sink: continue the run with the inbound value.
        return inputs.first
    }
}
