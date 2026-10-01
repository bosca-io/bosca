package bosca.segmentation.service

import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryService
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.scheduler.service.SchedulerService
import bosca.segmentation.configuration.SegmentationMigration
import bosca.segmentation.model.SegmentInput
import bosca.segmentation.model.SegmentStatus
import bosca.segmentation.model.SegmentType
import bosca.segmentation.repository.SegmentMemberRepositoryImpl
import bosca.segmentation.repository.SegmentRepositoryImpl
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.toJavaUuid

@OptIn(InternalDI::class)
class SegmentServiceIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5
                ),
                key = "test"
            )
        )
        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking {
                pool.close()
            }
            postgres.stop()
        }
        private val profileIds = List(5) { UUID.random() }
    }

    private lateinit var service: SegmentServiceImpl
    private lateinit var analyticsQueryExecutionService: AnalyticsQueryExecutionService
    private lateinit var analyticsQueryService: AnalyticsQueryService

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }

        if (!schemaInitialized) {
            withDb {
                connection().useStatement(
                    "CREATE TABLE IF NOT EXISTS public.profiles (id uuid PRIMARY KEY)"
                ) { it.execute() }
                connection().useStatement(
                    "CREATE TABLE IF NOT EXISTS public.analytics_queries (id uuid PRIMARY KEY)"
                ) { it.execute() }
            }
            runBlocking {
                FlywayMigration(pool).migrate(listOf(SegmentationMigration()))
            }
            schemaInitialized = true
        }

        withDb {
            transaction {
                connection().useStatement("DELETE FROM segmentation.segment_members") { it.execute() }
                connection().useStatement("DELETE FROM segmentation.segments") { it.execute() }
                connection().useStatement("DELETE FROM public.profiles") { it.execute() }
            }
            transaction {
                for (id in profileIds) {
                    connection().useStatement("INSERT INTO public.profiles (id) VALUES (?)") {
                        it.setObject(1, id.toJavaUuid())
                        it.execute()
                    }
                }
            }
        }

        analyticsQueryExecutionService = mockk(relaxed = true)
        analyticsQueryService = mockk(relaxed = true)
        service = SegmentServiceImpl(
            SegmentRepositoryImpl(),
            SegmentMemberRepositoryImpl(),
            analyticsQueryExecutionService,
            analyticsQueryService,
            mockk<SchedulerService>(relaxed = true)
        )
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) {
                    block()
                }
            } finally {
                withContext(NonCancellable) {
                    manager.release()
                }
            }
        }
    }

    // --- add ---

    @Test
    fun `add creates a static segment`() = withDb {
        val segment = service.add(SegmentInput(name = "Test Segment", type = SegmentType.STATIC))

        assertEquals("Test Segment", segment.name)
        assertEquals(SegmentType.STATIC, segment.type)
        assertEquals(SegmentStatus.DRAFT, segment.status)
        assertEquals(0, segment.memberCount)
        assertNotEquals(UUID.NIL, segment.id)
    }

    @Test
    fun `add creates segment with description and status`() = withDb {
        val segment = service.add(
            SegmentInput(
                name = "Active Segment",
                description = "A description",
                type = SegmentType.STATIC,
                status = SegmentStatus.ACTIVE
            )
        )

        assertEquals("Active Segment", segment.name)
        assertEquals("A description", segment.description)
        assertEquals(SegmentStatus.ACTIVE, segment.status)
    }

    // --- getById ---

    @Test
    fun `getById returns the segment`() = withDb {
        val created = service.add(SegmentInput(name = "Lookup", type = SegmentType.STATIC))

        val found = service.getById(created.id)

        assertNotNull(found)
        assertEquals(created.id, found.id)
        assertEquals("Lookup", found.name)
    }

    @Test
    fun `getById returns null for missing segment`() = withDb {
        assertNull(service.getById(UUID.random()))
    }

    // --- getAll ---

    @Test
    fun `getAll returns segments ordered by created desc`() = withDb {
        service.add(SegmentInput(name = "First", type = SegmentType.STATIC))
        service.add(SegmentInput(name = "Second", type = SegmentType.STATIC))
        service.add(SegmentInput(name = "Third", type = SegmentType.STATIC))

        val all = service.getAll(0, 10)

        assertEquals(3, all.size)
        assertEquals("Third", all[0].name)
        assertEquals("Second", all[1].name)
        assertEquals("First", all[2].name)
    }

    @Test
    fun `getAll supports pagination`() = withDb {
        service.add(SegmentInput(name = "A", type = SegmentType.STATIC))
        service.add(SegmentInput(name = "B", type = SegmentType.STATIC))
        service.add(SegmentInput(name = "C", type = SegmentType.STATIC))

        val page = service.getAll(1, 1)

        assertEquals(1, page.size)
        assertEquals("B", page[0].name)
    }

    // --- getByIds ---

    @Test
    fun `getByIds returns matching segments`() = withDb {
        val s1 = service.add(SegmentInput(name = "One", type = SegmentType.STATIC))
        service.add(SegmentInput(name = "Two", type = SegmentType.STATIC))
        val s3 = service.add(SegmentInput(name = "Three", type = SegmentType.STATIC))

        val results = service.getByIds(listOf(s1.id, s3.id))

        assertEquals(2, results.size)
        val names = results.map { it.name }.toSet()
        assertTrue(names.contains("One"))
        assertTrue(names.contains("Three"))
    }

    @Test
    fun `getByIds returns empty for empty input`() = withDb {
        assertEquals(emptyList(), service.getByIds(emptyList()))
    }

    // --- edit ---

    @Test
    fun `edit updates segment fields`() = withDb {
        val created = service.add(SegmentInput(name = "Original", type = SegmentType.STATIC))

        val updated = service.edit(
            created.id,
            SegmentInput(name = "Updated", description = "new desc", type = SegmentType.STATIC, status = SegmentStatus.ACTIVE)
        )

        assertEquals(created.id, updated.id)
        assertEquals("Updated", updated.name)
        assertEquals("new desc", updated.description)
        assertEquals(SegmentStatus.ACTIVE, updated.status)
    }

    @Test
    fun `edit fails for missing segment`() = withDb {
        assertFailsWith<IllegalStateException> {
            service.edit(UUID.random(), SegmentInput(name = "X", type = SegmentType.STATIC))
        }
    }

    // --- delete ---

    @Test
    fun `delete removes the segment`() = withDb {
        val created = service.add(SegmentInput(name = "ToDelete", type = SegmentType.STATIC))
        service.addMembers(created.id, listOf(profileIds[0]))

        service.delete(created.id)

        assertNull(service.getById(created.id))
    }

    // --- addMembers / getMembers / getMemberCount ---

    @Test
    fun `addMembers adds profiles to static segment`() = withDb {
        val segment = service.add(SegmentInput(name = "Members", type = SegmentType.STATIC))

        service.addMembers(segment.id, listOf(profileIds[0], profileIds[1]))

        val count = service.getMemberCount(segment.id)
        assertEquals(2, count)

        val members = service.getMembers(segment.id, 0, 100)
        assertEquals(2, members.size)
        val memberProfileIds = members.map { it.profileId }.toSet()
        assertTrue(memberProfileIds.contains(profileIds[0]))
        assertTrue(memberProfileIds.contains(profileIds[1]))
    }

    @Test
    fun `addMembers ignores duplicates`() = withDb {
        val segment = service.add(SegmentInput(name = "Dupes", type = SegmentType.STATIC))

        service.addMembers(segment.id, listOf(profileIds[0]))
        service.addMembers(segment.id, listOf(profileIds[0], profileIds[1]))

        assertEquals(2, service.getMemberCount(segment.id))
    }

    @Test
    fun `addMembers updates member count on segment`() = withDb {
        val segment = service.add(SegmentInput(name = "CountCheck", type = SegmentType.STATIC))

        service.addMembers(segment.id, listOf(profileIds[0], profileIds[1], profileIds[2]))

        val refreshed = service.getById(segment.id)
        assertNotNull(refreshed)
        assertEquals(3, refreshed.memberCount)
        assertNotNull(refreshed.lastEvaluated)
    }

    @Test
    fun `addMembers rejects dynamic segment`() = withDb {
        val segment = service.add(SegmentInput(name = "Dynamic", type = SegmentType.DYNAMIC))

        assertFailsWith<IllegalArgumentException> {
            service.addMembers(segment.id, listOf(profileIds[0]))
        }
    }

    // --- removeMembers ---

    @Test
    fun `removeMembers removes profiles from segment`() = withDb {
        val segment = service.add(SegmentInput(name = "RemoveTest", type = SegmentType.STATIC))
        service.addMembers(segment.id, listOf(profileIds[0], profileIds[1], profileIds[2]))

        service.removeMembers(segment.id, listOf(profileIds[1]))

        val count = service.getMemberCount(segment.id)
        assertEquals(2, count)
        val remaining = service.getMembers(segment.id, 0, 100).map { it.profileId }.toSet()
        assertTrue(remaining.contains(profileIds[0]))
        assertTrue(remaining.contains(profileIds[2]))
    }

    @Test
    fun `removeMembers silently ignores non-members`() = withDb {
        val segment = service.add(SegmentInput(name = "IgnoreNonMember", type = SegmentType.STATIC))
        service.addMembers(segment.id, listOf(profileIds[0]))

        service.removeMembers(segment.id, listOf(profileIds[1]))

        assertEquals(1, service.getMemberCount(segment.id))
    }

    @Test
    fun `dynamic evaluation rolls back existing membership when analytics result is stale`() = withDb {
        val queryId = UUID.random()
        connection().useStatement("INSERT INTO public.analytics_queries (id) VALUES (?)") {
            it.setObject(1, queryId.toJavaUuid())
            it.execute()
        }
        val segment = service.add(
            SegmentInput(
                name = "Stale dynamic segment",
                type = SegmentType.DYNAMIC,
                analyticsQueryId = queryId,
            ),
        )
        connection().useStatement(
            "INSERT INTO segmentation.segment_members (segment_id, profile_id) VALUES (?, ?)",
        ) {
            it.setObject(1, segment.id.toJavaUuid())
            it.setObject(2, profileIds[0].toJavaUuid())
            it.execute()
        }
        coEvery { analyticsQueryService.getParameters(queryId) } returns emptyList()
        coEvery { analyticsQueryExecutionService.execute(queryId, emptyList()) } returns
            AnalyticsQueryResponse(
                records = listOf(JsonObject(mapOf("profile_id" to JsonPrimitive(profileIds[1].toString())))),
                cached = true,
                refreshedAt = OffsetDateTime.parse("2026-07-28T12:00:00Z"),
                stale = true,
            )

        val failure = assertFailsWith<IllegalStateException> {
            service.evaluate(segment.id)
        }

        assertTrue(failure.message.orEmpty().contains("stale cached results"))
        assertEquals(1, service.getMemberCount(segment.id))
        assertEquals(profileIds[0], service.getMembers(segment.id, 0, 10).single().profileId)
        assertNull(service.getById(segment.id)?.lastEvaluated)
    }

    @Test
    fun `dynamic evaluation replaces membership when analytics result is fresh`() = withDb {
        val queryId = UUID.random()
        connection().useStatement("INSERT INTO public.analytics_queries (id) VALUES (?)") {
            it.setObject(1, queryId.toJavaUuid())
            it.execute()
        }
        val segment = service.add(
            SegmentInput(
                name = "Fresh dynamic segment",
                type = SegmentType.DYNAMIC,
                analyticsQueryId = queryId,
            ),
        )
        connection().useStatement(
            "INSERT INTO segmentation.segment_members (segment_id, profile_id) VALUES (?, ?)",
        ) {
            it.setObject(1, segment.id.toJavaUuid())
            it.setObject(2, profileIds[0].toJavaUuid())
            it.execute()
        }
        coEvery { analyticsQueryService.getParameters(queryId) } returns emptyList()
        coEvery { analyticsQueryExecutionService.execute(queryId, emptyList()) } returns
            AnalyticsQueryResponse(
                records = listOf(JsonObject(mapOf("profile_id" to JsonPrimitive(profileIds[1].toString())))),
            )

        val evaluated = service.evaluate(segment.id)

        assertEquals(1, evaluated.memberCount)
        assertNotNull(evaluated.lastEvaluated)
        assertEquals(profileIds[1], service.getMembers(segment.id, 0, 10).single().profileId)
    }

    // --- removeFromSegment ---

    @Test
    fun `removeFromSegment performs set subtraction`() = withDb {
        val target = service.add(SegmentInput(name = "Target", type = SegmentType.STATIC))
        val source = service.add(SegmentInput(name = "Source", type = SegmentType.STATIC))

        service.addMembers(target.id, listOf(profileIds[0], profileIds[1], profileIds[2]))
        service.addMembers(source.id, listOf(profileIds[1], profileIds[2], profileIds[3]))

        val result = service.removeFromSegment(target.id, source.id)

        assertEquals(1, result.memberCount)
        val remaining = service.getMembers(target.id, 0, 100).map { it.profileId }
        assertEquals(listOf(profileIds[0]), remaining)
    }

    @Test
    fun `removeFromSegment does not affect source segment`() = withDb {
        val target = service.add(SegmentInput(name = "Target", type = SegmentType.STATIC))
        val source = service.add(SegmentInput(name = "Source", type = SegmentType.STATIC))

        service.addMembers(target.id, listOf(profileIds[0], profileIds[1]))
        service.addMembers(source.id, listOf(profileIds[1]))

        service.removeFromSegment(target.id, source.id)

        assertEquals(1, service.getMemberCount(source.id))
    }

    @Test
    fun `removeFromSegment with no overlap leaves target unchanged`() = withDb {
        val target = service.add(SegmentInput(name = "Target", type = SegmentType.STATIC))
        val source = service.add(SegmentInput(name = "Source", type = SegmentType.STATIC))

        service.addMembers(target.id, listOf(profileIds[0]))
        service.addMembers(source.id, listOf(profileIds[1]))

        val result = service.removeFromSegment(target.id, source.id)

        assertEquals(1, result.memberCount)
    }

    @Test
    fun `removeFromSegment rejects dynamic target`() = withDb {
        val target = service.add(SegmentInput(name = "DynTarget", type = SegmentType.DYNAMIC))
        val source = service.add(SegmentInput(name = "Source", type = SegmentType.STATIC))

        assertFailsWith<IllegalArgumentException> {
            service.removeFromSegment(target.id, source.id)
        }
    }

    @Test
    fun `removeFromSegment fails for missing target`() = withDb {
        val source = service.add(SegmentInput(name = "Source", type = SegmentType.STATIC))

        assertFailsWith<IllegalStateException> {
            service.removeFromSegment(UUID.random(), source.id)
        }
    }

    @Test
    fun `removeFromSegment fails for missing source`() = withDb {
        val target = service.add(SegmentInput(name = "Target", type = SegmentType.STATIC))

        assertFailsWith<IllegalStateException> {
            service.removeFromSegment(target.id, UUID.random())
        }
    }

    // --- getAudienceProfileIds ---

    @Test
    fun `getAudienceProfileIds returns deduplicated profiles across segments`() = withDb {
        val s1 = service.add(SegmentInput(name = "S1", type = SegmentType.STATIC))
        val s2 = service.add(SegmentInput(name = "S2", type = SegmentType.STATIC))

        service.addMembers(s1.id, listOf(profileIds[0], profileIds[1]))
        service.addMembers(s2.id, listOf(profileIds[1], profileIds[2]))

        val audience = service.getAudienceProfileIds(listOf(s1.id, s2.id))

        assertEquals(3, audience.size)
        val set = audience.toSet()
        assertTrue(set.contains(profileIds[0]))
        assertTrue(set.contains(profileIds[1]))
        assertTrue(set.contains(profileIds[2]))
    }

    // --- getAudienceProfileIdsPaged ---

    @Test
    fun `getAudienceProfileIdsPaged returns paginated results`() = withDb {
        val s1 = service.add(SegmentInput(name = "Paged", type = SegmentType.STATIC))
        service.addMembers(s1.id, listOf(profileIds[0], profileIds[1], profileIds[2]))

        val page1 = service.getAudienceProfileIdsPaged(listOf(s1.id), 0, 2)
        val page2 = service.getAudienceProfileIdsPaged(listOf(s1.id), 2, 2)

        assertEquals(2, page1.size)
        assertEquals(1, page2.size)
        val all = (page1 + page2).toSet()
        assertEquals(3, all.size)
    }

    @Test
    fun `getAudienceProfileIdsPaged returns empty for empty input`() = withDb {
        assertEquals(emptyList(), service.getAudienceProfileIdsPaged(emptyList(), 0, 10))
    }

    // --- getAudienceCount ---

    @Test
    fun `getAudienceCount returns distinct count across segments`() = withDb {
        val s1 = service.add(SegmentInput(name = "C1", type = SegmentType.STATIC))
        val s2 = service.add(SegmentInput(name = "C2", type = SegmentType.STATIC))

        service.addMembers(s1.id, listOf(profileIds[0], profileIds[1]))
        service.addMembers(s2.id, listOf(profileIds[1], profileIds[2]))

        val count = service.getAudienceCount(listOf(s1.id, s2.id))

        assertEquals(3, count)
    }

    // --- getSegmentsByProfileId ---

    @Test
    fun `getSegmentsByProfileId returns segments containing the profile`() = withDb {
        val s1 = service.add(SegmentInput(name = "Seg1", type = SegmentType.STATIC))
        val s2 = service.add(SegmentInput(name = "Seg2", type = SegmentType.STATIC))
        val s3 = service.add(SegmentInput(name = "Seg3", type = SegmentType.STATIC))

        service.addMembers(s1.id, listOf(profileIds[0]))
        service.addMembers(s2.id, listOf(profileIds[0]))
        service.addMembers(s3.id, listOf(profileIds[1]))

        val segments = service.getSegmentsByProfileId(profileIds[0])

        assertEquals(2, segments.size)
        val ids = segments.map { it.id }.toSet()
        assertTrue(ids.contains(s1.id))
        assertTrue(ids.contains(s2.id))
    }

    @Test
    fun `getSegmentsByProfileId returns empty for unaffiliated profile`() = withDb {
        service.add(SegmentInput(name = "Lonely", type = SegmentType.STATIC))

        val segments = service.getSegmentsByProfileId(profileIds[4])

        assertTrue(segments.isEmpty())
    }

    // --- getMembers pagination ---

    @Test
    fun `getMembers supports pagination`() = withDb {
        val segment = service.add(SegmentInput(name = "PagedMembers", type = SegmentType.STATIC))
        service.addMembers(segment.id, profileIds.take(4))

        val page1 = service.getMembers(segment.id, 0, 2)
        val page2 = service.getMembers(segment.id, 2, 2)

        assertEquals(2, page1.size)
        assertEquals(2, page2.size)
        val all = (page1 + page2).map { it.profileId }.toSet()
        assertEquals(4, all.size)
    }
}
