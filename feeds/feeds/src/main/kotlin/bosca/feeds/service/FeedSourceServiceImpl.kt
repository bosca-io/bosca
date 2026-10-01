package bosca.feeds.service

import bosca.configuration.model.ConfigurationInput
import bosca.configuration.service.ConfigurationService
import bosca.db.transaction
import bosca.feeds.jobs.FetchFeedSourceExecutor
import bosca.feeds.jobs.FetchFeedSourceJob
import bosca.feeds.jobs.enqueue
import bosca.feeds.model.FeedAuthSecret
import bosca.feeds.model.FeedConfiguration
import bosca.feeds.model.FeedSource
import bosca.feeds.model.FeedSourceInput
import bosca.feeds.repository.FeedSourceRepository
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.service.SchedulerService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.source.model.SourceInput
import bosca.source.service.SourceService
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.Json

/**
 * Feed sources backed by the content `Source` (configuration) + the `feeds.feed_sources` record, with
 * the outbound-auth secret in `ConfigurationService` and a per-source `ScheduledJob` driving fetches.
 * An enabled source has a scheduled job (cron = its `cronInterval`) that enqueues a `FetchFeedSourceJob`;
 * disabling/deleting removes it. The scheduled job id is tracked on the record.
 */
@ServiceImplementation
class FeedSourceServiceImpl(
    private val sourceService: SourceService,
    private val repository: FeedSourceRepository,
    private val configurationService: ConfigurationService,
    private val schedulerService: SchedulerService,
    private val json: Json,
) : FeedSourceService {

    override suspend fun create(input: FeedSourceInput): FeedSource = transaction {
        val configuration = normalized(input.configuration)
        requireNoOverlap(configuration.url, excludeSourceId = null)
        val source = sourceService.add(
            SourceInput(name = input.name, description = input.description, configuration = encode(configuration)),
        )
        var feedSource = repository.add(
            FeedSource(
                sourceId = source.id,
                enabled = configuration.enabled,
                ownerProfileId = configuration.ownerProfileId,
                url = configuration.url,
            ),
        )
        input.authSecret?.let { storeAuthSecret(source.id, it) }
        if (configuration.enabled) {
            val jobId = scheduleFetch(source.id, configuration.cronInterval)
            repository.setScheduledJob(source.id, jobId)
            feedSource = feedSource.copy(scheduledJobId = jobId)
        }
        feedSource
    }

    override suspend fun update(sourceId: UUID, input: FeedSourceInput): FeedSource = transaction {
        val existing = repository.get(sourceId) ?: error("feed source $sourceId not found")
        val configuration = normalized(input.configuration)
        requireNoOverlap(configuration.url, excludeSourceId = sourceId)
        sourceService.edit(
            sourceId,
            SourceInput(name = input.name, description = input.description, configuration = encode(configuration)),
        )
        val updated = repository.update(
            sourceId = sourceId,
            enabled = configuration.enabled,
            ownerProfileId = configuration.ownerProfileId,
            url = configuration.url,
        ) ?: error("feed source $sourceId not found")
        input.authSecret?.let { storeAuthSecret(sourceId, it) }
        // Reschedule from scratch — the cron or enabled flag may have changed.
        unscheduleFetch(existing.scheduledJobId)
        val jobId = if (configuration.enabled) scheduleFetch(sourceId, configuration.cronInterval) else null
        repository.setScheduledJob(sourceId, jobId)
        updated.copy(scheduledJobId = jobId)
    }

    override suspend fun setEnabled(sourceId: UUID, enabled: Boolean): FeedSource? = transaction {
        val current = repository.get(sourceId) ?: return@transaction null
        if (enabled && current.scheduledJobId == null) {
            val configuration = getConfiguration(sourceId) ?: error("feed source $sourceId has no configuration")
            repository.setScheduledJob(sourceId, scheduleFetch(sourceId, configuration.cronInterval))
        } else if (!enabled && current.scheduledJobId != null) {
            unscheduleFetch(current.scheduledJobId)
            repository.setScheduledJob(sourceId, null)
        }
        repository.setEnabled(sourceId, enabled)
    }

    override suspend fun delete(sourceId: UUID): Boolean = transaction {
        val existing = repository.get(sourceId) ?: return@transaction false
        unscheduleFetch(existing.scheduledJobId)
        repository.softDelete(sourceId)
        val key = authSecretKey(sourceId)
        if (configurationService.getByKey(key) != null) configurationService.deleteConfiguration(key)
        true
    }

    override suspend fun fetchNow(sourceId: UUID): Boolean = transaction {
        repository.get(sourceId) ?: return@transaction false
        // Enqueue a FORCED fetch (skips the conditional-GET cache so the origin returns the full feed
        // even when unchanged) — the same durable job the scheduler uses, run on the feeds runner. The
        // enqueue defers to this transaction's commit (consistent with the platform job-queue contract).
        FetchFeedSourceJob(sourceId, force = true).enqueue()
        true
    }

    override suspend fun get(sourceId: UUID): FeedSource? = repository.get(sourceId)

    override suspend fun getAll(offset: Int, limit: Int): List<FeedSource> = repository.getAll(offset, limit)

    override suspend fun getByOwner(ownerProfileId: UUID, offset: Int, limit: Int): List<FeedSource> =
        repository.getByOwner(ownerProfileId, offset, limit)

    override suspend fun getConfiguration(sourceId: UUID): FeedConfiguration? {
        repository.get(sourceId) ?: return null
        val source = sourceService.getById(sourceId)
        return json.decodeFromJsonElement(FeedConfiguration.serializer(), source.configuration)
    }

    override suspend fun getAuthSecret(sourceId: UUID): String? {
        val configuration = configurationService.getByKey(authSecretKey(sourceId)) ?: return null
        val value = configurationService.getValue(configuration.id) ?: return null
        return json.decodeFromJsonElement(FeedAuthSecret.serializer(), value).secret
    }

    override suspend fun updateValidators(sourceId: UUID, etag: String?, lastModified: OffsetDateTime?) {
        repository.updateValidators(sourceId, etag, lastModified)
    }

    /** Create a per-source `ScheduledJob` that enqueues a [FetchFeedSourceJob] on [cron]; returns its id. */
    private suspend fun scheduleFetch(sourceId: UUID, cron: String): UUID =
        schedulerService.createJob(
            ScheduledJobInput(
                name = "Fetch feed source $sourceId",
                description = "Periodic fetch for feed source $sourceId",
                jobName = FetchFeedSourceExecutor.NAME,
                jobParameters = json.encodeToJsonElement(FetchFeedSourceJob.serializer(), FetchFeedSourceJob(sourceId)),
                cronExpression = cron,
                enabled = true,
                allowConcurrent = false,
            ),
            createdBy = UUID.NIL,
        ).id

    private suspend fun unscheduleFetch(scheduledJobId: UUID?) {
        scheduledJobId?.let { schedulerService.deleteJob(it) }
    }

    private fun normalized(configuration: FeedConfiguration): FeedConfiguration =
        configuration.copy(url = configuration.url.ifBlank { normalizeUrl(configuration.endpoint) })

    private fun encode(configuration: FeedConfiguration) =
        json.encodeToJsonElement(FeedConfiguration.serializer(), configuration)

    private suspend fun storeAuthSecret(sourceId: UUID, secret: String) {
        val key = authSecretKey(sourceId)
        val value = json.encodeToJsonElement(FeedAuthSecret.serializer(), FeedAuthSecret(secret))
        val existing = configurationService.getByKey(key)
        if (existing != null) {
            configurationService.setValue(existing.id, value)
        } else {
            configurationService.setConfiguration(
                ConfigurationInput(
                    key = key,
                    description = "Outbound auth secret for feed source $sourceId",
                    value = value,
                    public = false,
                    permissions = emptyList(),
                ),
            )
        }
    }

    private fun authSecretKey(sourceId: UUID): String = "feeds.source.$sourceId.auth"

    /**
     * Rejects a canonical URL already claimed by a different live source. A user source cannot overlap a
     * Bosca-managed source, and no two live sources may share a URL — the partial-unique index on
     * `feeds.feed_sources.url` is the DB backstop; this turns a would-be constraint violation
     * into a clear domain error. [excludeSourceId] lets a source keep its own URL on update.
     */
    private suspend fun requireNoOverlap(url: String, excludeSourceId: UUID?) {
        val existing = repository.getByUrl(url)
        if (existing != null && existing.sourceId != excludeSourceId) {
            error("a feed source already exists for $url")
        }
    }

    /**
     * Canonicalizes a feed endpoint into the overlap key: lowercases the (case-insensitive) scheme and
     * host, drops the default port (80/443) and any fragment, and trims a trailing slash — leaving the
     * case-sensitive path and query intact. Falls back to a trimmed, slash-stripped string for inputs
     * that aren't parseable absolute URLs (e.g. opaque API identifiers).
     */
    private fun normalizeUrl(endpoint: String): String {
        val trimmed = endpoint.trim()
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if (scheme == null || host == null) return trimmed.trimEnd('/')
            val defaultPort = (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)
            val portPart = if (uri.port == -1 || defaultPort) "" else ":${uri.port}"
            val path = (uri.rawPath ?: "").trimEnd('/')
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            "$scheme://$host$portPart$path$query"
        } catch (e: URISyntaxException) {
            trimmed.trimEnd('/')
        }
    }
}
