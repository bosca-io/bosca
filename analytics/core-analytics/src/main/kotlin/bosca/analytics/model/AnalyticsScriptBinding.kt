package bosca.analytics.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Binds a stored scripting [Script][bosca.scripting.model.Script] to analytics events so **every**
 * event batch flows through the script inline in the processor's transform chain — bypassing the
 * durable pipeline run machinery. The script receives the whole batch and filters it however it
 * likes; there is no event-type routing at the binding level.
 *
 * This is the cheap counterpart to triggering a pipeline: a pipeline run mints a durable
 * `PipelineRun`, dispatch/run jobs, checkpoints, and run-log history (the "bookkeeping price"),
 * which is too expensive for high-throughput analytics. A binding instead runs its script
 * in-process via [ScriptExecutionService][bosca.scripting.service.ScriptExecutionService] with the
 * event batch as input.
 *
 * @property id stable identifier for this binding
 * @property scriptId the scripting-module script to run over every batch
 * @property transform whether the script's returned batch replaces the events flowing through the
 *   processing flow. When `true`, the script's output (an `Events` batch) becomes the batch for the
 *   next binding and the rest of the flow ("pass through" / filter). When `false`, the script runs
 *   for its side effects only and its output is ignored — it can never mutate the analytics stream.
 * @property enabled when false the binding is skipped entirely (soft off-switch)
 * @property ordinal execution order; bindings run ascending by ordinal, so lower numbers see the
 *   batch first and (for transforms) their output feeds higher-ordinal bindings
 * @property created row creation timestamp
 * @property modified row last-modified timestamp
 */
@Serializable
data class AnalyticsScriptBinding(
    @Contextual
    val id: UUID,
    @Contextual
    @ColumnName("script_id")
    val scriptId: UUID,
    val transform: Boolean = true,
    val enabled: Boolean = true,
    val ordinal: Int = 0,
    @Contextual
    val created: OffsetDateTime,
    @Contextual
    val modified: OffsetDateTime,
)
