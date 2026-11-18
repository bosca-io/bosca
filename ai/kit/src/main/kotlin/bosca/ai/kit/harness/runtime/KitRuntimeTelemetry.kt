package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.*
import bosca.counter.Counter
import bosca.sharedqueue.jobs.DelayException
import bosca.telemetry.Tracing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import org.slf4j.Logger

/** Metadata-only operational events and bounded minute counters. Metrics are best-effort, never execution authority. */
class KitRuntimeTelemetry(private val counter: Counter, private val json: Json,
                          private val log: Logger = LoggerFactory.getLogger(KitRuntimeTelemetry::class.java)) {

    suspend fun record(operation: KitRuntimeOperation, outcome: String, session: KitRuntimeSession? = null,
                       turn: KitRuntimeTurn? = null, approval: KitRuntimeApproval? = null,
                       durationMillis: Long? = null, bytes: Long? = null, ageMillis: Long? = null,
                       integration: String? = null, actor: String = "runner", metrics: Boolean = true, failure: Throwable? = null) {
        require(outcome in OUTCOMES && actor in setOf("runner", "principal"))
        val fields = buildJsonObject {
            put("event", "kit.runtime"); put("operation", operation.name.lowercase()); put("outcome", outcome); put("actor", actor)
            failure?.let { put("errorType", it.javaClass.name) }
            Tracing.currentTraceId()?.let { put("traceId", it) }
            session?.let {
                put("sessionId", it.sessionId.toString()); put("principalId", it.principalId.toString())
                put("generation", it.generation); put("epoch", it.epoch); put("state", it.state)
                it.dispatchId?.let { id -> put("dispatchId", id.toString()) }
                // Select fields explicitly: these snapshots also contain settings, scopes and secret references.
                val resolution = (turn?.resolvedSettings ?: it.resolvedSettings)?.let { value -> decode(KitHarnessResolution.serializer(), value) }
                resolution?.let { value -> put("configurationId", value.configurationId.toString()); put("policyVersion", value.policyVersion) }
                val context = (turn?.contextManifest ?: it.contextManifest)?.let { value -> decode(KitContextManifest.serializer(), value) }
                context?.let { value ->
                    put("contextRevision", value.revision); put("instructionRevision", value.instructionRevision)
                    putJsonArray("sources") { value.sources.forEach { source -> add(buildJsonObject {
                        put("kind", source.kind); put("id", source.id); put("version", source.version)
                        put("revision", source.revision); put("contentAllowed", source.contentAllowed)
                    }) } }
                }
            }
            (turn?.id ?: approval?.turnId)?.let { put("turnId", it.toString()) }
            approval?.let { put("approvalId", it.id.toString()) }
            integration?.let { put("integration", it) }
            durationMillis?.let { put("milliseconds", it.coerceAtLeast(0)) }
            bytes?.let { put("bytes", it.coerceAtLeast(0)) }
            ageMillis?.let { put("ageMilliseconds", it.coerceAtLeast(0)) }
        }
        log.info("{}", fields.toString())
        if (!metrics) return
        val prefix = "kit.runtime.${operation.name.lowercase()}.$outcome"
        val minute = System.currentTimeMillis() / 60000
        metric {
            counter.increment("$prefix.count.$minute")
            durationMillis?.let {
                counter.increment("$prefix.milliseconds.$minute", it.coerceAtLeast(0))
                counter.increment("$prefix.milliseconds_samples.$minute")
            }
            bytes?.let {
                counter.increment("$prefix.bytes.$minute", it.coerceAtLeast(0))
                counter.increment("$prefix.bytes_samples.$minute")
            }
            ageMillis?.let {
                counter.increment("$prefix.age_milliseconds.$minute", it.coerceAtLeast(0))
                counter.increment("$prefix.age_milliseconds_samples.$minute")
            }
        }
    }

    /** Measures an attempt; durable startup/turn/cleanup measurements are recorded at their committed boundary. */
    suspend fun <T> observe(operation: KitRuntimeOperation, session: KitRuntimeSession? = null,
                            bytes: Long? = null, ageMillis: Long? = null, block: suspend () -> T): T {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        return try {
            block().also { record(operation, "success", session, durationMillis = elapsed(), bytes = bytes, ageMillis = ageMillis) }
        } catch (cancelled: CancellationException) {
            record(operation, "cancelled", session, durationMillis = elapsed(), metrics = false, failure = cancelled)
            throw cancelled
        } catch (pending: DelayException) {
            record(operation, "pending", session, durationMillis = elapsed())
            throw pending
        } catch (failure: Exception) {
            record(operation, "failure", session, durationMillis = elapsed(), failure = failure)
            throw failure
        }
    }

    /** Global sampled totals: divide each total by samples to obtain the mean across recovery snapshots. */
    suspend fun sample(active: Long, failed: Long) {
        log.info("{}", buildJsonObject {
            put("event", "kit.runtime.snapshot"); put("active", active); put("failed", failed)
            Tracing.currentTraceId()?.let { put("traceId", it) }
        }.toString())
        val minute = System.currentTimeMillis() / 60000
        metric {
            counter.increment("kit.runtime.snapshot.samples.$minute")
            counter.increment("kit.runtime.snapshot.active.$minute", active)
            counter.increment("kit.runtime.snapshot.failed.$minute", failed)
        }
    }

    private suspend fun metric(block: suspend () -> Unit) {
        try {
            if (withTimeoutOrNull(1000) { block(); true } == null) log.warn("Kit runtime metrics unavailable")
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { log.warn("Kit runtime metrics unavailable", failure) }
    }

    private fun <T> decode(serializer: KSerializer<T>, value: JsonElement): T? =
        try { json.decodeFromJsonElement(serializer, value) } catch (_: SerializationException) { null }

    private companion object {
        val OUTCOMES = setOf("success", "failure", "pending", "cancelled", "interrupted", "started", "denied", "unavailable")
    }
}

internal fun elapsedSince(started: bosca.serialization.OffsetDateTime?): Long? =
    started?.let { java.time.Duration.between(it, bosca.serialization.OffsetDateTime.now()).toMillis().coerceAtLeast(0) }
