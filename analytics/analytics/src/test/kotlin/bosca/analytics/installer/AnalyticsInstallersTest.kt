package bosca.analytics.installer

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.model.QueryParameterType
import bosca.analytics.service.AnalyticsQueryService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnalyticsInstallersTest {
    private val service = mockk<AnalyticsQueryService>()
    private val security = mockk<SecurityService>()
    private val installation = mockk<PackageInstallation>(relaxed = true)
    private val version = mockk<PackageInstallationVersion>(relaxed = true)
    private val administrators = Group(UUID.random(), "administrators", "Administrators", GroupType.SYSTEM)

    private fun query(input: AnalyticsQueryInput) = AnalyticsQuery(
        id = if (input.id == UUID.NIL) UUID.random() else input.id,
        key = input.key,
        name = input.name,
        description = input.description,
        query = input.query,
        configuration = input.configuration,
        refreshIntervalSeconds = input.refreshIntervalSeconds,
    )

    @Test
    fun `query installer creates all missing queries with view and execute permissions`() = runTest {
        coEvery { service.getQueries(0, Int.MAX_VALUE) } returns emptyList()
        coEvery { security.getGroupByName("administrators", GroupType.SYSTEM) } returns administrators
        val addedQueries = mutableListOf<AnalyticsQueryInput>()
        coEvery { service.addQuery(any()) } answers {
            val input = firstArg<AnalyticsQueryInput>()
            addedQueries += input
            query(input)
        }
        coEvery { service.addPermission(any()) } returns mockk()
        val installer = QueryInstaller(service, security)

        installer.install(installation, version)

        assertEquals("1.0.7", installer.version)
        coVerify(exactly = 7) { service.addQuery(any()) }
        coVerify(exactly = 14) { service.addPermission(any()) }
        coVerify(exactly = 0) { service.editQuery(any()) }
        val metrics = listOf("daily-active-users", "unique-sessions-per-day", "monthly-users-and-engagement")
        metrics.forEach { key ->
            val input = addedQueries.single { it.key == key }
            assertTrue(input.query.contains("type = 'Session'"), key)
            assertTrue(input.query.contains("json_extract_scalar(json_parse("), key)
            assertTrue(input.query.contains("'$.start') = 'true'"), key)
            assertTrue(input.query.contains("created >= :startDate"), key)
            assertTrue(input.query.contains("created < :endDate + interval '1' day"), key)
            assertTrue(input.query.contains("""'Chrome/[0-9]+\.0\.[1-9]'"""), key)
            assertTrue(input.query.contains("'Windows NT|Macintosh|X11'"), key)
            assertEquals(listOf("startDate", "endDate"), input.parameters.map { it.parameter }, key)
            assertTrue(input.parameters.all { it.type == QueryParameterType.DATE && it.required }, key)
            assertEquals(43_200, input.refreshIntervalSeconds, key)
        }
        val dailyUsers = addedQueries.single { it.key == "daily-active-users" }.query
        assertTrue(dailyUsers.contains("installation_users as ("))
        assertTrue(dailyUsers.contains("having count(distinct user_id) = 1"))
        assertTrue(dailyUsers.contains("iu.date = s.date and iu.installation_id = s.installation_id"))
        assertTrue(dailyUsers.contains("count(distinct coalesce(s.user_id, iu.user_id, s.installation_id))"))
        assertTrue(dailyUsers.contains("where s.is_start and not s.crawler"))
        val sessions = addedQueries.single { it.key == "unique-sessions-per-day" }.query
        assertTrue(sessions.contains("count(distinct nullif(context.session_id, ''))"))
        val monthly = addedQueries.single { it.key == "monthly-users-and-engagement" }.query
        assertTrue(monthly.contains("iu.month = e.month and iu.installation_id = e.installation_id"))
        assertTrue(monthly.contains("lead(e.created) over (partition by e.installation_id, e.session_id order by e.created)"))
        assertTrue(monthly.contains("0), 300000) as engaged_ms"))
        assertTrue(monthly.contains("type not in ('Heartbeat', 'Installation', 'Assignment')"))
        assertTrue(monthly.contains("where active"))
        listOf("total-organizations", "total-profiles").forEach { key ->
            assertEquals(43_200, addedQueries.single { it.key == key }.refreshIntervalSeconds, key)
        }
        val pageImpressions = addedQueries.single { it.key == "top-50-page-impressions" }.query
        assertTrue(pageImpressions.contains("and type = 'Impression'"))
        assertTrue(pageImpressions.contains("and element.type = 'page'"))
        assertTrue(pageImpressions.contains("count(distinct impression_id)"))
        assertTrue(pageImpressions.contains("coalesce(nullif(client_id, ''), cast(id as varchar))"))
        assertTrue(pageImpressions.contains("group by date, page_id"))
        (metrics + "top-50-page-impressions").forEach { key ->
            val sql = addedQueries.single { it.key == key }.query
            assertTrue(sql.contains("context.browser.agent"), key)
            assertTrue(sql.contains("facebookexternalhit|chatgpt-user"), key)
        }
    }

    @Test
    fun `query installer updates every existing seeded query`() = runTest {
        val existing = listOf(
            "daily-active-users",
            "unique-sessions-per-day",
            "monthly-users-and-engagement",
            "total-organizations",
            "total-profiles",
            "top-50-page-impressions",
            "store-release-telemetry",
        ).map { AnalyticsQuery(UUID.random(), it, it, it, "select 1") }
        coEvery { service.getQueries(0, Int.MAX_VALUE) } returns existing
        coEvery { security.getGroupByName("administrators", GroupType.SYSTEM) } returns administrators
        coEvery { service.editQuery(any()) } answers { query(firstArg()) }

        QueryInstaller(service, security).install(installation, version)

        existing.forEach { stored ->
            coVerify(exactly = 1) {
                service.editQuery(match { it.key == stored.key && it.id == stored.id })
            }
        }
        coVerify(exactly = 0) { service.addQuery(any()) }
    }

    @Test
    fun `query installer fails loudly when administrators group is missing`() = runTest {
        coEvery { service.getQueries(0, Int.MAX_VALUE) } returns emptyList()
        coEvery { security.getGroupByName("administrators", GroupType.SYSTEM) } returns null

        assertFailsWith<IllegalStateException> {
            QueryInstaller(service, security).install(installation, version)
        }
    }

    @Test
    fun `raw events installer creates the missing parameterized query`() = runTest {
        coEvery { service.getQueries(0, Int.MAX_VALUE) } returns emptyList()
        coEvery { security.getGroupByName("administrators", GroupType.SYSTEM) } returns administrators
        coEvery { service.addQuery(any()) } answers { query(firstArg()) }
        coEvery { service.addPermission(any()) } returns mockk()
        val installer = RawEventsQueryInstaller(service, security)

        installer.install(installation, version)

        assertEquals("1.0.0", installer.version)
        coVerify(exactly = 1) {
            service.addQuery(match { it.key == "raw-events" && it.parameters.size == 4 })
        }
        coVerify(exactly = 2) { service.addPermission(any()) }
    }

    @Test
    fun `raw events installer scopes the warehouse table`() = runTest {
        coEvery { service.getQueries(0, Int.MAX_VALUE) } returns emptyList()
        coEvery { security.getGroupByName("administrators", GroupType.SYSTEM) } returns administrators
        coEvery { service.addQuery(any()) } answers { query(firstArg()) }
        coEvery { service.addPermission(any()) } returns mockk()

        RawEventsQueryInstaller(service, security, "siteb_warehouse.bosca.events")
            .install(installation, version)

        coVerify(exactly = 1) {
            service.addQuery(match { it.query.contains("from siteb_warehouse.bosca.events") })
        }
        assertEquals("events", safeEventsTable(" events "))
        assertEquals("bosca.events", safeEventsTable("bosca.events"))
    }

    @Test
    fun `invalid warehouse settings fall back to the defaults instead of failing startup`() = runTest {
        coEvery { service.getQueries(0, Int.MAX_VALUE) } returns emptyList()
        coEvery { security.getGroupByName("administrators", GroupType.SYSTEM) } returns administrators
        val addedQueries = mutableListOf<AnalyticsQueryInput>()
        coEvery { service.addQuery(capture(addedQueries)) } answers { query(firstArg()) }
        coEvery { service.addPermission(any()) } returns mockk()

        QueryInstaller(service, security, "warehouse.bosca.events; drop table", "bosca; drop")
            .install(installation, version)

        assertEquals(DEFAULT_EVENTS_TABLE, safeEventsTable("a.b.c.d"))
        assertEquals(DEFAULT_POSTGRES_CATALOG, safePostgresCatalog("site.bosca"))
        addedQueries.forEach { input -> assertFalse(input.query.contains("drop"), input.key) }
        assertTrue(addedQueries.single { it.key == "daily-active-users" }.query.contains("from warehouse.bosca.events"))
        assertTrue(addedQueries.single { it.key == "total-profiles" }.query.contains("from bosca.\"public\".profiles"))
    }

    @Test
    fun `query installer scopes warehouse and operational catalogs to the installation`() = runTest {
        coEvery { service.getQueries(0, Int.MAX_VALUE) } returns emptyList()
        coEvery { security.getGroupByName("administrators", GroupType.SYSTEM) } returns administrators
        val addedQueries = mutableListOf<AnalyticsQueryInput>()
        coEvery { service.addQuery(capture(addedQueries)) } answers { query(firstArg()) }
        coEvery { service.addPermission(any()) } returns mockk()

        QueryInstaller(service, security, "siteb_warehouse.bosca.events", "siteb_bosca").install(installation, version)

        val catalogReference = Regex("""(?i)\b(?:from|join)\s+([A-Za-z_][A-Za-z0-9_]*)\.""")
        addedQueries.forEach { input ->
            val catalogs = catalogReference.findAll(input.query).mapTo(mutableSetOf()) { it.groupValues[1] }
            assertTrue(catalogs.all { it in setOf("siteb_bosca", "siteb_warehouse") }, "${input.key}: $catalogs")
        }
        assertTrue(addedQueries.single { it.key == "total-organizations" }.query.contains("siteb_bosca.\"public\".organizations"))
        assertTrue(addedQueries.single { it.key == "total-profiles" }.query.contains("siteb_bosca.\"public\".profiles"))
    }

    @Test
    fun `raw events installer preserves an existing customized query`() = runTest {
        coEvery { service.getQueries(0, Int.MAX_VALUE) } returns listOf(
            AnalyticsQuery(UUID.random(), "raw-events", "Custom", "Custom", "select custom"),
        )

        RawEventsQueryInstaller(service, security).install(installation, version)

        coVerify(exactly = 0) { security.getGroupByName(any(), any()) }
        coVerify(exactly = 0) { service.addQuery(any()) }
    }

    @Test
    fun `raw events installer fails loudly when administrators group is missing`() = runTest {
        coEvery { service.getQueries(0, Int.MAX_VALUE) } returns emptyList()
        coEvery { security.getGroupByName("administrators", GroupType.SYSTEM) } returns null

        assertFailsWith<IllegalStateException> {
            RawEventsQueryInstaller(service, security).install(installation, version)
        }
    }
}
