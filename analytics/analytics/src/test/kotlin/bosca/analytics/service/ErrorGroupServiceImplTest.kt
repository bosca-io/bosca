package bosca.analytics.service

import bosca.analytics.model.Device
import bosca.analytics.model.ErrorGroup
import bosca.analytics.model.ErrorGroupStatus
import bosca.analytics.model.ErrorInfo
import bosca.analytics.model.Event
import bosca.analytics.model.EventContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.repository.ErrorGroupRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class ErrorGroupServiceImplTest {

    /**
     * In-memory fake of [ErrorGroupRepository] that mirrors the Postgres
     * upsert semantics defined in V150__error_groups.sql so the service
     * can be exercised end-to-end without a database.
     */
    private class FakeErrorGroupRepository : ErrorGroupRepository {
        val store = LinkedHashMap<String, ErrorGroup>()
        var recordCalls = 0
        var failNext = false
        var nextFailure: Throwable? = null

        /** Reverses the LIKE escape applied by [ErrorGroupServiceImpl.escapeLikePattern]
         *  so the in-memory `contains` check matches the same strings as PostgreSQL's
         *  `ILIKE '%' || :search || '%'` would after backslash-unescaping. */
        private fun unescapeLike(value: String): String =
            value.replace("\\_", "_").replace("\\%", "%").replace("\\\\", "\\")

        override suspend fun getByFingerprint(fingerprint: String): ErrorGroup? = store[fingerprint]

        override suspend fun list(
            appId: String?,
            status: ErrorGroupStatus?,
            fatal: Boolean?,
            search: String?,
            offset: Long,
            limit: Int,
        ): List<ErrorGroup> = store.values
            .filter { appId == null || it.appId == appId }
            .filter { status == null || it.status == status }
            .filter { fatal == null || it.fatal == fatal }
            .filter { search == null || it.message.contains(unescapeLike(search), ignoreCase = true) || it.type.contains(unescapeLike(search), ignoreCase = true) }
            .sortedByDescending { it.lastSeen }
            .drop(offset.toInt())
            .take(limit)

        override suspend fun count(
            appId: String?,
            status: ErrorGroupStatus?,
            fatal: Boolean?,
            search: String?,
        ): Long = list(appId, status, fatal, search, 0, Int.MAX_VALUE).size.toLong()

        override suspend fun recordOccurrence(
            fingerprint: String,
            appId: String,
            type: String,
            message: String,
            fatal: Boolean,
            firstSeen: OffsetDateTime,
            lastSeen: OffsetDateTime,
            count: Long,
            sampleEventId: String?,
            sampleStack: String?,
        ) {
            recordCalls += 1
            nextFailure?.let {
                nextFailure = null
                throw it
            }
            if (failNext) {
                failNext = false
                error("simulated db failure")
            }
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val existing = store[fingerprint]
            store[fingerprint] = if (existing == null) {
                ErrorGroup(
                    fingerprint = fingerprint,
                    appId = appId,
                    type = type,
                    message = message,
                    fatal = fatal,
                    status = ErrorGroupStatus.OPEN,
                    assigneeId = null,
                    firstSeen = firstSeen,
                    lastSeen = lastSeen,
                    eventCount = count,
                    sampleEventId = sampleEventId,
                    sampleStack = sampleStack,
                    aiSummary = null,
                    aiSummaryAt = null,
                    created = now,
                    modified = now,
                )
            } else {
                // Match V150's on-conflict semantics: always refresh sample
                // fields with the latest batch's representative, always
                // advance first/last seen via min/max, and flip RESOLVED
                // back to OPEN on regression. IGNORED groups stay ignored.
                val newStatus = if (existing.status == ErrorGroupStatus.RESOLVED) {
                    ErrorGroupStatus.OPEN
                } else {
                    existing.status
                }
                existing.copy(
                    eventCount = existing.eventCount + count,
                    firstSeen = if (firstSeen.isBefore(existing.firstSeen)) firstSeen else existing.firstSeen,
                    lastSeen = if (lastSeen.isAfter(existing.lastSeen)) lastSeen else existing.lastSeen,
                    message = message,
                    sampleEventId = sampleEventId,
                    sampleStack = sampleStack,
                    fatal = fatal,
                    status = newStatus,
                    modified = now,
                )
            }
        }

        override suspend fun setStatus(fingerprint: String, status: ErrorGroupStatus): ErrorGroup? {
            val existing = store[fingerprint] ?: return null
            val updated = existing.copy(status = status, modified = OffsetDateTime.now(ZoneOffset.UTC))
            store[fingerprint] = updated
            return updated
        }

        override suspend fun setAssignee(fingerprint: String, assigneeId: UUID?): ErrorGroup? {
            val existing = store[fingerprint] ?: return null
            val updated = existing.copy(assigneeId = assigneeId, modified = OffsetDateTime.now(ZoneOffset.UTC))
            store[fingerprint] = updated
            return updated
        }

        override suspend fun setAiSummary(fingerprint: String, summary: String): ErrorGroup? {
            val existing = store[fingerprint] ?: return null
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val updated = existing.copy(aiSummary = summary, aiSummaryAt = now, modified = now)
            store[fingerprint] = updated
            return updated
        }
    }

    private fun device() = Device(
        installationId = "i", manufacturer = "m", model = "m", platform = "p",
        primaryLocale = "en", systemName = "s", timezone = "UTC", type = "t", version = "v",
    )

    private fun events(appId: String, vararg events: Event): Events =
        Events(
            context = EventContext(appId = appId, appVersion = "1.0.0", device = device(), sessionId = "s"),
            events = events.toList(),
            sent = 0L,
            sentMicros = 0L,
        )

    private fun errorEvent(
        created: Long,
        fingerprint: String?,
        type: String? = "java.lang.IllegalStateException",
        message: String = "boom",
        fatal: Boolean = false,
        stack: String? = "at com.example.Foo.bar(Foo.kt:1)",
        clientId: String? = null,
    ): Event = Event(
        created = created,
        type = EventType.Error,
        clientId = clientId,
        error = ErrorInfo(message = message, type = type, stackTrace = stack, fatal = fatal, fingerprint = fingerprint),
    )

    @Test
    fun `recordBatch is a no-op when there are no error events`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(events("app-1", Event(created = 0L, type = EventType.Session)))
        assertEquals(0, repo.recordCalls)
        assertTrue(repo.store.isEmpty())
    }

    @Test
    fun `recordBatch is a no-op when error events lack fingerprints`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(events("app-1", errorEvent(created = 0L, fingerprint = null)))
        assertEquals(0, repo.recordCalls)
    }

    @Test
    fun `recordBatch creates a new group on first occurrence`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(events("app-1", errorEvent(created = 100L, fingerprint = "fp-1", clientId = "c-1")))
        assertEquals(1, repo.recordCalls)
        val group = repo.store["fp-1"]!!
        assertEquals(1L, group.eventCount)
        assertEquals(ErrorGroupStatus.OPEN, group.status)
        assertEquals("c-1", group.sampleEventId)
        assertEquals("app-1", group.appId)
    }

    @Test
    fun `recordBatch issues one upsert per fingerprint regardless of event count`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(
            events(
                "app-1",
                errorEvent(created = 100L, fingerprint = "fp-1"),
                errorEvent(created = 200L, fingerprint = "fp-1"),
                errorEvent(created = 300L, fingerprint = "fp-1"),
                errorEvent(created = 100L, fingerprint = "fp-2"),
            )
        )
        assertEquals(2, repo.recordCalls, "should have one upsert per distinct fingerprint, not per event")
        assertEquals(3L, repo.store["fp-1"]!!.eventCount)
        assertEquals(1L, repo.store["fp-2"]!!.eventCount)
    }

    @Test
    fun `recordBatch increments existing group counts on subsequent batches`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(events("app-1", errorEvent(created = 100L, fingerprint = "fp-1")))
        service.recordBatch(
            events(
                "app-1",
                errorEvent(created = 200L, fingerprint = "fp-1"),
                errorEvent(created = 300L, fingerprint = "fp-1"),
            )
        )
        assertEquals(3L, repo.store["fp-1"]!!.eventCount)
    }

    @Test
    fun `recordBatch tracks first seen and last seen across batches`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(events("app-1", errorEvent(created = 200L, fingerprint = "fp-1")))
        service.recordBatch(events("app-1", errorEvent(created = 100L, fingerprint = "fp-1")))
        service.recordBatch(events("app-1", errorEvent(created = 500L, fingerprint = "fp-1")))
        val group = repo.store["fp-1"]!!
        assertEquals(100L, group.firstSeen.toInstant().toEpochMilli())
        assertEquals(500L, group.lastSeen.toInstant().toEpochMilli())
    }

    @Test
    fun `recordBatch newest event in a batch wins as the sample`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(
            events(
                "app-1",
                errorEvent(created = 100L, fingerprint = "fp-1", message = "early", clientId = "c-early"),
                errorEvent(created = 300L, fingerprint = "fp-1", message = "late", clientId = "c-late"),
                errorEvent(created = 200L, fingerprint = "fp-1", message = "middle", clientId = "c-middle"),
            )
        )
        // The newest sample wins as the representative
        assertEquals("c-late", repo.store["fp-1"]!!.sampleEventId)
    }

    @Test
    fun `resolved groups regress to open and refresh sample on new occurrence`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(events("app-1", errorEvent(created = 100L, fingerprint = "fp-1", message = "v1", clientId = "c-1")))
        service.setStatus("fp-1", ErrorGroupStatus.RESOLVED)
        service.recordBatch(events("app-1", errorEvent(created = 200L, fingerprint = "fp-1", message = "v2", clientId = "c-2")))
        val group = repo.store["fp-1"]!!
        assertEquals(ErrorGroupStatus.OPEN, group.status, "regression should flip status back to open")
        assertEquals("v2", group.message, "regression should refresh the sample message")
        assertEquals("c-2", group.sampleEventId, "regression should refresh the sample event id")
        assertEquals(2L, group.eventCount)
    }

    @Test
    fun `open groups refresh their sample on new occurrences`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(events("app-1", errorEvent(created = 100L, fingerprint = "fp-1", message = "v1")))
        service.recordBatch(events("app-1", errorEvent(created = 200L, fingerprint = "fp-1", message = "v2")))
        // The sample always refreshes with the latest batch so the
        // admin UI's stack trace pane stays current (see the on-conflict
        // clause in ErrorGroupRepository).
        assertEquals("v2", repo.store["fp-1"]!!.message)
    }

    @Test
    fun `ignored groups stay ignored on new occurrences`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(events("app-1", errorEvent(created = 100L, fingerprint = "fp-1")))
        service.setStatus("fp-1", ErrorGroupStatus.IGNORED)
        service.recordBatch(events("app-1", errorEvent(created = 200L, fingerprint = "fp-1")))
        assertEquals(ErrorGroupStatus.IGNORED, repo.store["fp-1"]!!.status)
    }

    @Test
    fun `AI summary is capped to MAX_AI_SUMMARY_CHARS`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(events("app-1", errorEvent(created = 100L, fingerprint = "fp-1")))
        val huge = "x".repeat(ErrorGroupServiceImpl.MAX_AI_SUMMARY_CHARS + 10_000)
        val updated = service.setAiSummary("fp-1", huge)
        assertEquals(ErrorGroupServiceImpl.MAX_AI_SUMMARY_CHARS, updated.aiSummary!!.length)
    }

    @Test
    fun `recordBatch swallows repository errors so ingestion is never broken`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        repo.failNext = true
        service.recordBatch(events("app-1", errorEvent(created = 100L, fingerprint = "fp-1")))
        // No exception thrown, repository state unchanged for the failed call
        assertNull(repo.store["fp-1"])
    }

    @Test
    fun `recordBatch is a no-op when the events context has no appId`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(
            Events(
                context = null,
                events = listOf(errorEvent(created = 100L, fingerprint = "fp-1")),
                sent = 0L,
                sentMicros = 0L,
            )
        )
        assertEquals(0, repo.recordCalls)
    }

    @Test
    fun `recordBatch skips missing blank and unusable error contexts`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(
            Events(
                context = null,
                events = listOf(Event(created = 1, type = EventType.Session)),
                sent = 0,
                sentMicros = 0,
            ),
        )
        service.recordBatch(events("   ", errorEvent(2, "fingerprint")))
        service.recordBatch(
            events(
                "app",
                Event(created = 3, type = EventType.Error, error = null),
                errorEvent(4, null),
                errorEvent(5, "usable"),
            ),
        )
        assertEquals(setOf("usable"), repo.store.keys)
    }

    @Test
    fun `newest sample retains non-null fallback fields`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(
            events(
                "app",
                errorEvent(1, "fp", type = "Known", stack = "stack", clientId = "client"),
                errorEvent(2, "fp", type = null, stack = null, clientId = null),
            ),
        )
        val group = repo.store.getValue("fp")
        assertEquals("Known", group.type)
        assertEquals("stack", group.sampleStack)
        assertEquals("client", group.sampleEventId)

        populatedWithNullType(service, repo)
    }

    private suspend fun populatedWithNullType(service: ErrorGroupServiceImpl, repo: FakeErrorGroupRepository) {
        service.recordBatch(events("app", errorEvent(10, "null-type", type = null)))
        assertEquals("UnknownError", repo.store.getValue("null-type").type)
        service.recordBatch(
            events(
                "app",
                errorEvent(30, "out-of-order"),
                errorEvent(20, "out-of-order"),
            ),
        )
        assertEquals(20, repo.store.getValue("out-of-order").firstSeen.toInstant().toEpochMilli())
    }

    @Test
    fun `mutations fail for missing groups and preserve uncapped summaries`() = runTest {
        val service = ErrorGroupServiceImpl(FakeErrorGroupRepository())
        assertFailsWith<IllegalStateException> { service.setStatus("missing", ErrorGroupStatus.OPEN) }
        assertFailsWith<IllegalStateException> { service.assign("missing", null) }
        assertFailsWith<IllegalStateException> { service.setAiSummary("missing", "summary") }

        val repo = FakeErrorGroupRepository()
        val populated = ErrorGroupServiceImpl(repo)
        populated.recordBatch(events("app", errorEvent(1, "fp")))
        assertEquals("short", populated.setAiSummary("fp", "short").aiSummary)
    }

    @Test
    fun `recordBatch preserves repository cancellation`() = runTest {
        val repo = FakeErrorGroupRepository().apply { nextFailure = CancellationException("cancel") }
        val service = ErrorGroupServiceImpl(repo)
        assertFailsWith<CancellationException> {
            service.recordBatch(events("app", errorEvent(1, "fp")))
        }
    }

    @Test
    fun `setStatus and assign return the updated group`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(events("app-1", errorEvent(created = 100L, fingerprint = "fp-1")))

        val resolved = service.setStatus("fp-1", ErrorGroupStatus.RESOLVED)
        assertEquals(ErrorGroupStatus.RESOLVED, resolved.status)

        val assigneeId = UUID.random()
        val assigned = service.assign("fp-1", assigneeId)
        assertEquals(assigneeId, assigned.assigneeId)
    }

    @Test
    fun `list and count delegate filtering to the repository`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(events("app-1", errorEvent(created = 100L, fingerprint = "fp-1", type = "AppError", message = "database down")))
        service.recordBatch(events("app-2", errorEvent(created = 200L, fingerprint = "fp-2", type = "AppError", message = "out of memory", fatal = true)))

        assertEquals(2L, service.count())
        assertEquals(1L, service.count(appId = "app-2"))
        assertEquals(1L, service.count(fatal = true))
        assertEquals(1L, service.count(search = "memory"))
        assertEquals(1, service.list(search = "database").size)
    }

    @Test
    fun `getByFingerprint round trips`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(events("app-1", errorEvent(created = 100L, fingerprint = "fp-1")))
        val fetched = service.getByFingerprint("fp-1")
        assertNotNull(fetched)
        assertEquals("fp-1", fetched.fingerprint)
        assertNull(service.getByFingerprint("missing"))
    }

    @Test
    fun `escapeLikePattern escapes LIKE wildcards for literal substring matching`() {
        assertEquals(null, ErrorGroupServiceImpl.escapeLikePattern(null))
        assertEquals("hello", ErrorGroupServiceImpl.escapeLikePattern("hello"))
        assertEquals("100\\%", ErrorGroupServiceImpl.escapeLikePattern("100%"))
        assertEquals("user\\_name", ErrorGroupServiceImpl.escapeLikePattern("user_name"))
        assertEquals("back\\\\slash", ErrorGroupServiceImpl.escapeLikePattern("back\\slash"))
        assertEquals("\\%\\_\\\\all", ErrorGroupServiceImpl.escapeLikePattern("%_\\all"))
    }

    @Test
    fun `recordBatch caps message and type to their maximum lengths`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        val longType = "t".repeat(ErrorGroupServiceImpl.MAX_TYPE_CHARS + 5_000)
        val longMessage = "m".repeat(ErrorGroupServiceImpl.MAX_MESSAGE_CHARS + 10_000)
        service.recordBatch(events("app-1", errorEvent(created = 100L, fingerprint = "fp-1", type = longType, message = longMessage)))
        val group = repo.store["fp-1"]!!
        assertEquals(ErrorGroupServiceImpl.MAX_TYPE_CHARS, group.type.length)
        assertEquals(ErrorGroupServiceImpl.MAX_MESSAGE_CHARS, group.message.length)
    }

    @Test
    fun `list and count escape search wildcards before querying`() = runTest {
        val repo = FakeErrorGroupRepository()
        val service = ErrorGroupServiceImpl(repo)
        service.recordBatch(events("app-1", errorEvent(created = 100L, fingerprint = "fp-1", message = "100% failure")))

        // Searching for literal "100%" should find the group. Without escaping,
        // "%" would match everything and "_" would match any single character.
        assertEquals(1L, service.count(search = "100%"))
        assertEquals(1, service.list(search = "100%").size)
        // Literal underscore should not match "100% failure" because _ is escaped
        assertEquals(0L, service.count(search = "100_"))
    }
}
