package bosca.analytics.service

import bosca.analytics.model.ErrorGroup
import bosca.analytics.model.ErrorGroupStatus
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.repository.ErrorGroupRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset

@ServiceImplementation
class ErrorGroupServiceImpl(
    private val repository: ErrorGroupRepository,
) : ErrorGroupService {

    override suspend fun recordBatch(events: Events) {
        val appId = events.context?.appId
        if (appId.isNullOrBlank()) {
            val errorCount = events.events.count { it.type == EventType.Error }
            if (errorCount > 0) {
                log.warn(
                    "Skipping {} error event(s) in batch because context.appId is missing; " +
                        "this indicates a pipeline configuration issue",
                    errorCount,
                )
            }
            return
        }
        val allErrors = events.events.mapNotNull { event ->
            if (event.type != EventType.Error) return@mapNotNull null
            event.error?.let { event to it }
        }
        if (allErrors.isEmpty()) return

        val missingFingerprints = allErrors.count { (_, error) -> error.fingerprint == null }
        if (missingFingerprints > 0) {
            log.warn(
                "Skipping {} error event(s) without fingerprint in batch for appId={}; " +
                    "this usually indicates the ErrorFingerprintTransform did not run",
                missingFingerprints,
                appId,
            )
        }

        val errorEvents = allErrors.mapNotNull { (event, error) ->
            error.fingerprint?.let { fingerprint -> Triple(event, error, fingerprint) }
        }
        if (errorEvents.isEmpty()) return

        // Group by fingerprint and pre-aggregate per-batch counts + min/max timestamps,
        // retaining the newest event in the batch as the representative sample. Ties
        // on `created` deterministically keep the first-seen sample (strict `>`).
        data class Aggregate(
            var count: Long,
            var firstSeen: Long,
            var lastSeen: Long,
            var sampleCreated: Long,
            var sampleType: String,
            var sampleMessage: String,
            var sampleFatal: Boolean,
            var sampleStack: String?,
            var sampleEventId: String?,
        )

        val aggregates = HashMap<String, Aggregate>()
        for ((event, error, fingerprint) in errorEvents) {
            val agg = aggregates[fingerprint]
            if (agg == null) {
                aggregates[fingerprint] = Aggregate(
                    count = 1L,
                    firstSeen = event.created,
                    lastSeen = event.created,
                    sampleCreated = event.created,
                    sampleType = error.type ?: "UnknownError",
                    sampleMessage = error.message,
                    sampleFatal = error.fatal,
                    sampleStack = error.stackTrace,
                    sampleEventId = event.clientId,
                )
            } else {
                agg.count += 1
                if (event.created < agg.firstSeen) agg.firstSeen = event.created
                if (event.created > agg.lastSeen) agg.lastSeen = event.created
                // Strict `>` so ties on `created` keep the earlier-iterated sample —
                // deterministic, matches the sibling in-memory fake used by tests.
                if (event.created > agg.sampleCreated) {
                    agg.sampleCreated = event.created
                    agg.sampleType = error.type ?: agg.sampleType
                    agg.sampleMessage = error.message
                    agg.sampleFatal = error.fatal
                    agg.sampleStack = error.stackTrace ?: agg.sampleStack
                    agg.sampleEventId = event.clientId ?: agg.sampleEventId
                }
            }
        }

        for ((fingerprint, agg) in aggregates) {
            try {
                repository.recordOccurrence(
                    fingerprint = fingerprint,
                    appId = appId,
                    type = agg.sampleType.take(MAX_TYPE_CHARS),
                    message = agg.sampleMessage.take(MAX_MESSAGE_CHARS),
                    fatal = agg.sampleFatal,
                    firstSeen = agg.firstSeen.toOffsetDateTime(),
                    lastSeen = agg.lastSeen.toOffsetDateTime(),
                    count = agg.count,
                    sampleEventId = agg.sampleEventId,
                    sampleStack = agg.sampleStack,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never let group bookkeeping break the ingestion pipeline.
                log.error("Failed to record error group occurrence: fingerprint={}", fingerprint, e)
            }
        }
    }

    override suspend fun getByFingerprint(fingerprint: String): ErrorGroup? =
        repository.getByFingerprint(fingerprint)

    override suspend fun list(
        appId: String?,
        status: ErrorGroupStatus?,
        fatal: Boolean?,
        search: String?,
        offset: Long,
        limit: Int,
    ): List<ErrorGroup> = repository.list(appId, status, fatal, escapeLikePattern(search), offset, limit)

    override suspend fun count(
        appId: String?,
        status: ErrorGroupStatus?,
        fatal: Boolean?,
        search: String?,
    ): Long = repository.count(appId, status, fatal, escapeLikePattern(search))

    override suspend fun setStatus(fingerprint: String, status: ErrorGroupStatus): ErrorGroup =
        repository.setStatus(fingerprint, status)
            ?: error("Error group not found: $fingerprint")

    override suspend fun assign(fingerprint: String, assigneeId: UUID?): ErrorGroup =
        repository.setAssignee(fingerprint, assigneeId)
            ?: error("Error group not found: $fingerprint")

    override suspend fun setAiSummary(fingerprint: String, summary: String): ErrorGroup {
        val capped = if (summary.length > MAX_AI_SUMMARY_CHARS) {
            summary.substring(0, MAX_AI_SUMMARY_CHARS)
        } else summary
        return repository.setAiSummary(fingerprint, capped)
            ?: error("Error group not found: $fingerprint")
    }

    private fun Long.toOffsetDateTime(): OffsetDateTime =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneOffset.UTC)

    companion object {
        private val log = LoggerFactory.getLogger(ErrorGroupService::class.java)

        /** Maximum length of an AI-generated summary persisted on the
         *  group row. 64K chars accommodates detailed multi-section
         *  analyses with embedded code snippets while still bounding
         *  the worst case from a buggy or malicious LLM. */
        internal const val MAX_AI_SUMMARY_CHARS = 65_536

        /** Maximum length of the error type string persisted on the
         *  group row. Prevents unbounded storage from malicious or
         *  buggy clients. */
        internal const val MAX_TYPE_CHARS = 8_192

        /** Maximum length of the error message string persisted on the
         *  group row. Prevents unbounded storage from malicious or
         *  buggy clients. */
        internal const val MAX_MESSAGE_CHARS = 65_536

        /** Escapes LIKE/ILIKE wildcards (`%`, `_`) and the escape
         *  character itself (`\`) so user-supplied search terms are
         *  treated as literal substrings by PostgreSQL. */
        internal fun escapeLikePattern(value: String?): String? {
            if (value == null) return null
            return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
        }
    }
}
