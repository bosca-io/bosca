package bosca.graphql.server

import bosca.graphql.language.Document
import bosca.graphql.schema.GraphQLSchema
import kotlinx.serialization.json.JsonElement

/**
 * Hooks fired around the phases of a request — the graphql-java `Instrumentation` role. Each `beginX`
 * is called when a phase starts and returns an [InstrumentationPhase] whose [InstrumentationPhase.onCompleted] runs
 * when that phase finishes (with its result or the throwable that ended it) — the seam for timing, metrics, tracing.
 *
 * [instrumentExecution] is the abort hook: returning a non-empty error list rejects the operation *before*
 * execution (how [MaxQueryDepthInstrumentation] / [MaxQueryComplexityInstrumentation] enforce limits).
 *
 * To override only what you need, extend [SimpleInstrumentation] (every hook is a no-op there) rather than
 * implementing this interface directly — graphql-java's `SimpleInstrumentation` pattern.
 */
interface Instrumentation {
    suspend fun beginParse(query: String): InstrumentationPhase<Document>

    suspend fun beginValidation(document: Document): InstrumentationPhase<List<GraphQLError>>

    suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult>

    suspend fun beginField(parameters: FieldParameters): InstrumentationPhase<JsonElement>

    /** Errors returned here reject the operation before execution; an empty list lets it proceed. */
    suspend fun instrumentExecution(parameters: ExecutionParameters): List<GraphQLError>

    companion object {
        /** An instrumentation that does nothing — every hook is a no-op. */
        val NONE: Instrumentation = object : SimpleInstrumentation() {}

        /** Combine several instrumentations: phases fan out in order, and abort errors concatenate. */
        fun of(vararg instrumentations: Instrumentation): Instrumentation =
            instrumentations.toList().let { if (it.size == 1) it.single() else ChainedInstrumentation(it) }
    }
}

/** An [Instrumentation] whose every hook is a no-op; override only the ones you need. */
abstract class SimpleInstrumentation : Instrumentation {
    override suspend fun beginParse(query: String): InstrumentationPhase<Document> = noopPhase()

    override suspend fun beginValidation(document: Document): InstrumentationPhase<List<GraphQLError>> = noopPhase()

    override suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult> = noopPhase()

    override suspend fun beginField(parameters: FieldParameters): InstrumentationPhase<JsonElement> = noopPhase()

    override suspend fun instrumentExecution(parameters: ExecutionParameters): List<GraphQLError> = emptyList()
}

/**
 * The handle returned by a `beginX` hook. [onCompleted] runs exactly once when the phase ends, with either its result
 * or the error that ended it.
 */
fun interface InstrumentationPhase<in T> {
    fun onCompleted(result: T?, error: Throwable?)
}

private val NOOP_PHASE = InstrumentationPhase<Any?> { _, _ -> }

/** The shared no-op phase, reusable at any element type thanks to [InstrumentationPhase]'s contravariance. */
fun <T> noopPhase(): InstrumentationPhase<T> = NOOP_PHASE

/**
 * Notify this phase of [error] without allowing a callback failure to replace the operation or field failure.
 */
internal fun <T> InstrumentationPhase<T>.completeExceptionally(error: Throwable) {
    try {
        onCompleted(null, error)
    } catch (completionError: Throwable) {
        if (completionError !== error) error.addSuppressed(completionError)
    }
}

/**
 * Run synchronous phase work and notify this phase exactly once. A completion failure on successful work propagates;
 * a completion failure on failed work is suppressed onto the original error.
 */
internal inline fun <T> InstrumentationPhase<T>.completeCatching(block: () -> T): Result<T> {
    val result = try {
        block()
    } catch (error: Throwable) {
        completeExceptionally(error)
        return Result.failure(error)
    }
    onCompleted(result, null)
    return Result.success(result)
}

/** What an [Instrumentation] sees about a whole operation. */
data class ExecutionParameters(
    val document: Document,
    val operationName: String?,
    val variables: Map<String, Any?>,
    val schema: GraphQLSchema,
)

/** What an [Instrumentation] sees about a single field resolution. */
data class FieldParameters(
    val parentType: String,
    val fieldName: String,
    val path: List<Any>,
)

/** Fans each phase out across [instrumentations] (in order) and concatenates their abort errors. */
private class ChainedInstrumentation(private val instrumentations: List<Instrumentation>) : Instrumentation {
    override suspend fun beginParse(query: String) = beginPhases { it.beginParse(query) }

    override suspend fun beginValidation(document: Document) = beginPhases { it.beginValidation(document) }

    override suspend fun beginExecution(parameters: ExecutionParameters) = beginPhases { it.beginExecution(parameters) }

    override suspend fun beginField(parameters: FieldParameters) = beginPhases { it.beginField(parameters) }

    override suspend fun instrumentExecution(parameters: ExecutionParameters): List<GraphQLError> =
        instrumentations.flatMap { it.instrumentExecution(parameters) }

    private suspend fun <T> beginPhases(
        begin: suspend (Instrumentation) -> InstrumentationPhase<T>,
    ): InstrumentationPhase<T> {
        val phases = ArrayList<InstrumentationPhase<T>>(instrumentations.size)
        try {
            instrumentations.forEach { instrumentation -> phases += begin(instrumentation) }
        } catch (error: Throwable) {
            phases.forEach { phase -> phase.completeExceptionally(error) }
            throw error
        }
        return combine(phases)
    }

    private fun <T> combine(phases: List<InstrumentationPhase<T>>): InstrumentationPhase<T> =
        InstrumentationPhase { result, error ->
            var failure: Throwable? = null
            phases.forEach { phase ->
                try {
                    phase.onCompleted(result, error)
                } catch (completionError: Throwable) {
                    if (completionError === error) return@forEach
                    val firstFailure = failure
                    if (firstFailure == null) {
                        failure = completionError
                    } else if (completionError !== firstFailure) {
                        firstFailure.addSuppressed(completionError)
                    }
                }
            }
            failure?.let { throw it }
        }
}
