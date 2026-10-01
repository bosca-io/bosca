package bosca.ai.kit.tools.analytics

import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.chat.model.AnalyticsInvestigationStep
import bosca.ai.kit.agents.analytics.InvestigationAnnotation
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Ambient, concurrency-safe recorder for the work an analytics run actually performs.
 *
 * Sequence numbers are allocated atomically and reads are sorted by that number, so concurrent tool
 * completions cannot reorder the chain. Model annotations can explain recorded work, but [assemble]
 * drops annotations that do not match a recorded step and retains every unannotated record.
 */
open class InvestigationRecorder : CoroutineContext.Element {

    private val nextSequence = AtomicInteger()
    private val recorded = CopyOnWriteArrayList<AnalyticsInvestigationStep>()

    /** Recorded steps in execution order. */
    val steps: List<AnalyticsInvestigationStep>
        get() = recorded.sortedBy { it.sequence }

    /** The SQL of the most recent successful query step, if one ran. */
    val lastQuery: String?
        get() = steps.lastOrNull { it.sql != null }?.sql

    /** Capture the time immediately before a tool starts work. */
    fun startedAt(): String = OffsetDateTime.now().toString()

    /** Record a successfully completed tool execution and return its allocated sequence number. */
    fun record(
        kind: AnalyticsInvestigationKind,
        tool: String,
        resultSummary: String,
        sql: String? = null,
        startedAt: String = startedAt(),
    ): Int {
        val sequence = nextSequence.incrementAndGet()
        recorded += AnalyticsInvestigationStep(
            sequence = sequence,
            kind = kind,
            tool = tool,
            sql = sql,
            resultSummary = resultSummary,
            startedAt = startedAt,
        )
        return sequence
    }

    /** Resolve a model-claimed query to the latest matching statement that actually ran. */
    fun findExecuted(query: String?): String? {
        val target = normalize(query ?: return null)
        if (target.isEmpty()) return null
        return steps.lastOrNull { it.sql?.let(::normalize)?.equals(target, ignoreCase = true) == true }?.sql
    }

    /**
     * Join model explanations onto recorded facts. A sequence match is authoritative; when an
     * annotation also supplies SQL, both must match. SQL-only annotations match the latest execution
     * of that statement. No annotation can introduce a step that was not recorded.
     */
    fun assemble(annotations: List<InvestigationAnnotation>): List<AnalyticsInvestigationStep> {
        val matched = buildMap<Int, InvestigationAnnotation> {
            annotations.forEach { annotation ->
                val step = when {
                    annotation.sequence != null -> steps.firstOrNull { candidate ->
                        candidate.sequence == annotation.sequence &&
                            (annotation.sql.isNullOrBlank() || candidate.sql?.let(::normalize)?.equals(normalize(annotation.sql), ignoreCase = true) == true)
                    }
                    !annotation.sql.isNullOrBlank() -> {
                        val target = normalize(annotation.sql)
                        steps.lastOrNull { it.sql?.let(::normalize)?.equals(target, ignoreCase = true) == true }
                    }
                    else -> null
                }
                if (step != null) put(step.sequence, annotation)
            }
        }
        return steps.map { step ->
            matched[step.sequence]?.let { annotation ->
                step.copy(
                    purpose = annotation.purpose.takeIf { it.isNotBlank() },
                    conclusion = annotation.conclusion.takeIf { it.isNotBlank() },
                )
            } ?: step
        }
    }

    companion object Key : CoroutineContext.Key<InvestigationRecorder> {
        private val WHITESPACE = Regex("\\s+")

        internal fun normalize(sql: String): String =
            sql.trim().trimEnd(';').trim().replace(WHITESPACE, " ")
    }

    override val key: CoroutineContext.Key<*> get() = Key
}
