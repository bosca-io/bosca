package bosca.experimentation.jobs

import bosca.analytics.model.EventType
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ExperimentActivationFilter
import bosca.experimentation.model.GoalMetricType
import bosca.serialization.UUID
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [buildPerUserEventCountsQuery] — the pure SQL
 * builder that feeds the CUPED per-user queries. Exercised here
 * without a Trino connection so the SQL shape and parameter
 * binding are pinned independently of the JDBC execution path.
 *
 * The tests cover:
 *
 *   1. Every filter combination appends the right condition and
 *      binds the right parameter value. Eventtype specifically
 *      binds the Iceberg-format capitalized name, not the
 *      wire-format SerialName — silent drift there would produce
 *      zero rows and a bogus CUPED adjustment of 0.
 *   2. Time-bound formatting matches Trino's canonical
 *      `yyyy-MM-dd HH:mm:ss.SSSSSS` form in UTC, so the CAST to
 *      TIMESTAMP doesn't silently coerce a wrong timezone.
 *   3. An unbounded time window (windowStart only) still emits a
 *      `created >= ?` bound — the builder must never produce an
 *      unbounded table scan.
 *   4. The events table identifier is injected from the caller
 *      (ExperimentationConfig), not hardcoded — so operators can
 *      point the aggregator at different warehouses without
 *      touching this file.
 */
class CupedTrinoQueryTest {

    private fun ts(iso: String): OffsetDateTime =
        OffsetDateTime.parse(iso)

    // -----------------------------------------------------------------
    // Basic SQL shape
    // -----------------------------------------------------------------

    @Test
    fun `builds a grouped query when only a time window is set`() {
        val query = buildPerUserEventCountsQuery(
            eventsTable = "analytics.events",
            eventType = null,
            elementType = null,
            elementId = null,
            pagePath = null,
            windowStart = ts("2025-01-01T00:00:00Z"),
            windowEnd = null,
        )
        assertEquals(
            "SELECT CASE WHEN context.user_id IS NOT NULL THEN CONCAT('principal:', context.user_id) ELSE CONCAT('installation:', context.device.installation_id) END AS client_id, count(DISTINCT coalesce(client_id, CAST(id AS VARCHAR))) AS cnt FROM analytics.events " +
                "WHERE ((type <> 'Impression' OR element.type = 'page') AND NOT (type = 'Interaction' AND coalesce(element.type, '') in ('scroll_depth', 'scroll_max_depth'))) AND created >= CAST(? AS TIMESTAMP) GROUP BY CASE WHEN context.user_id IS NOT NULL THEN CONCAT('principal:', context.user_id) ELSE CONCAT('installation:', context.device.installation_id) END",
            query.sql,
        )
        assertEquals(1, query.params.size)
        // Trino canonical UTC form: yyyy-MM-dd HH:mm:ss.SSSSSS
        // with no `T` separator and no offset suffix.
        val bound = query.params.single()
        assertTrue(bound.startsWith("2025-01-01 00:00:00"),
            "bound time should be in Trino canonical UTC form, got '$bound'")
    }

    @Test
    fun `CUPED page prefixes and exact path form one alternative predicate before item and time filters`() {
        for (pagePath in listOf(null, "/featured")) {
            val query = buildPerUserEventCountsQuery(
                eventsTable = "analytics.events",
                eventType = EventType.Impression,
                elementType = "page",
                elementId = null,
                pagePath = pagePath,
                pagePathPrefixes = listOf("/articles/", "/talks/"),
                itemExtraKey = "campaign",
                itemExtraValue = "",
                windowStart = ts("2025-01-01T00:00:00Z"),
                windowEnd = ts("2025-01-15T00:00:00Z"),
            )
            val alternatives = if (pagePath == null) {
                "(starts_with(page.path, ?) OR starts_with(page.path, ?))"
            } else {
                "(page.path = ? OR starts_with(page.path, ?) OR starts_with(page.path, ?))"
            }
            assertTrue(query.sql.contains("element.type = ? AND $alternatives AND cardinality(element.content) > 0"))
            assertTrue(query.sql.contains("json_extract_scalar(try(json_parse(element.extras)), '$.campaign') = ?"))
            assertEquals(
                listOf("Impression", "page") + listOfNotNull(pagePath) + listOf(
                    "/articles/", "/talks/", "", "2025-01-01 00:00:00.000000", "2025-01-15 00:00:00.000000",
                ),
                query.params,
            )
        }
    }

    @Test
    fun `adds all four filter conditions when set`() {
        val query = buildPerUserEventCountsQuery(
            eventsTable = "warehouse.events",
            eventType = EventType.Interaction,
            elementType = "click",
            elementId = "signup-btn",
            pagePath = "/checkout",
            windowStart = ts("2025-01-01T00:00:00Z"),
            windowEnd = ts("2025-01-15T00:00:00Z"),
        )
        // All four filter conditions plus both time bounds = 6
        // predicates.
        assertTrue(query.sql.contains("type = ?"),
            "query should include type filter, got: ${query.sql}")
        assertTrue(query.sql.contains("element.type = ?"),
            "query should include element.type filter")
        assertTrue(query.sql.contains("element.id = ?"),
            "query should include element.id filter")
        assertTrue(query.sql.contains("page.path = ?"),
            "query should include page.path filter")
        assertTrue(query.sql.contains("created >= CAST(? AS TIMESTAMP)"))
        assertTrue(query.sql.contains("created < CAST(? AS TIMESTAMP)"))
        assertEquals(6, query.params.size,
            "four filters + two time bounds = six params")
    }

    // -----------------------------------------------------------------
    // Event type is bound in the Iceberg-canonical form
    // -----------------------------------------------------------------

    @Test
    fun `eventType is bound as the Iceberg-format capitalized name`() {
        // The Iceberg events table stores the enum's Kotlin .name
        // (PascalCase) via IcebergEventsToRecordTransform. Binding
        // the wire-format SerialName ("interaction") would silently
        // match zero rows. This assertion pins the correct form.
        val query = buildPerUserEventCountsQuery(
            eventsTable = "events",
            eventType = EventType.Interaction,
            elementType = null,
            elementId = null,
            pagePath = null,
            windowStart = ts("2025-01-01T00:00:00Z"),
            windowEnd = null,
        )
        assertEquals("Interaction", query.params.first(),
            "eventType param must be the PascalCase storage form, not the wire-format serial name")
        assertTrue(!query.sql.contains("type <> 'Impression'"))
        assertTrue(query.sql.contains("coalesce(element.type, '') in ('scroll_depth', 'scroll_max_depth')"))
    }

    @Test
    fun `an explicit scroll metric includes the requested scroll measurements`() {
        val query = buildPerUserEventCountsQuery(
            eventsTable = "events",
            eventType = EventType.Interaction,
            elementType = "scroll_depth",
            elementId = null,
            pagePath = null,
            windowStart = ts("2025-01-01T00:00:00Z"),
            windowEnd = null,
        )

        assertTrue(query.sql.contains("type = ?"))
        assertTrue(query.sql.contains("element.type = ?"))
        assertTrue(!query.sql.contains("NOT ("))
        assertEquals(listOf("Interaction", "scroll_depth"), query.params.take(2))
    }

    @Test
    fun `an explicit impression metric includes passive impressions`() {
        val query = buildPerUserEventCountsQuery(
            eventsTable = "events",
            eventType = EventType.Impression,
            elementType = null,
            elementId = null,
            pagePath = null,
            windowStart = ts("2025-01-01T00:00:00Z"),
            windowEnd = null,
        )

        assertTrue(query.sql.contains("type = ?"))
        assertTrue(!query.sql.contains("type <> 'Impression'"))
        assertEquals("Impression", query.params.first())
    }

    @Test
    fun `every EventType variant binds its Kotlin enum name verbatim`() {
        // Regression guard paired with
        // ConversionGoalEventTypeSerializationTest — the
        // per-user query and the main aggregator must bind the
        // same form for every variant. If either one drifts, the
        // CUPED aggregator would silently correlate against the
        // wrong event type.
        for (type in EventType.entries) {
            val query = buildPerUserEventCountsQuery(
                eventsTable = "events",
                eventType = type,
                elementType = null,
                elementId = null,
                pagePath = null,
                windowStart = ts("2025-01-01T00:00:00Z"),
                windowEnd = null,
            )
            assertEquals(type.name, query.params.first(),
                "${type.name} should bind as '${type.name}'")
        }
    }

    // -----------------------------------------------------------------
    // Parameter order matches the WHERE clause order
    // -----------------------------------------------------------------

    @Test
    fun `params order matches WHERE clause order exactly`() {
        val query = buildPerUserEventCountsQuery(
            eventsTable = "events",
            eventType = EventType.Impression,
            elementType = "page",
            elementId = "home",
            pagePath = "/",
            windowStart = ts("2025-01-01T00:00:00Z"),
            windowEnd = ts("2025-01-31T00:00:00Z"),
        )
        // The builder appends filters in a fixed order:
        //   eventType, elementType, elementId, pagePath, startTs, endTs
        assertEquals("Impression", query.params[0])
        assertEquals("page", query.params[1])
        assertEquals("home", query.params[2])
        assertEquals("/", query.params[3])
        assertTrue(query.params[4].startsWith("2025-01-01"))
        assertTrue(query.params[5].startsWith("2025-01-31"))
    }

    // -----------------------------------------------------------------
    // Table name injection
    // -----------------------------------------------------------------

    @Test
    fun `events table identifier is injected verbatim into the FROM clause`() {
        val query = buildPerUserEventCountsQuery(
            eventsTable = "prod_warehouse.analytics.events_v2",
            eventType = null,
            elementType = null,
            elementId = null,
            pagePath = null,
            windowStart = ts("2025-01-01T00:00:00Z"),
            windowEnd = null,
        )
        assertTrue(
            query.sql.contains("FROM prod_warehouse.analytics.events_v2"),
            "events table identifier must be injected verbatim, got: ${query.sql}",
        )
    }

    // -----------------------------------------------------------------
    // UTC normalization of OffsetDateTime inputs
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    // buildConversionCountQuery — the main aggregator's SQL builder
    // -----------------------------------------------------------------

    private fun goal(
        metricType: GoalMetricType,
        eventType: EventType? = null,
        elementType: String? = null,
        elementId: String? = null,
        pagePath: String? = null,
        pagePathPrefixes: List<String> = emptyList(),
        itemExtraKey: String? = null,
        itemExtraValue: String? = null,
    ) = ConversionGoal(
        id = UUID.NIL,
        experimentId = UUID.NIL,
        name = "t",
        eventType = eventType,
        elementType = elementType,
        elementId = elementId,
        metricType = metricType,
        pagePath = pagePath,
        pagePathPrefixes = pagePathPrefixes,
        itemExtraKey = itemExtraKey,
        itemExtraValue = itemExtraValue,
    )

    @Test
    fun `UNIQUE_CONVERSION joins assignments and deduplicates by assignment id`() {
        val built = assertNotNull(
            buildConversionCountQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(GoalMetricType.UNIQUE_CONVERSION, eventType = EventType.Interaction),
                startDate = ts("2025-01-01T00:00:00Z"),
                endDate = null,
            )
        )
        assertTrue(built.sql.startsWith("WITH assignment_rows AS"))
        assertTrue(built.sql.contains("SELECT DISTINCT CAST(subject_id AS VARCHAR) AS client_id, variation_key"))
        assertTrue(built.sql.contains("INNER JOIN experiment_subjects"))
        assertTrue(built.sql.contains("candidates.created >= subjects.assigned_at"))
        assertTrue(built.sql.contains("CONCAT('installation:', context.device.installation_id)"))
        assertTrue(built.sql.contains("coalesce(element.type, '') in ('scroll_depth', 'scroll_max_depth')"))
        assertEquals(UUID.NIL.toString(), built.params.first())
    }

    @Test
    fun `an explicitly selected scroll element can be a conversion metric`() {
        val built = assertNotNull(
            buildConversionCountQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(
                    GoalMetricType.EVENT_COUNT,
                    eventType = EventType.Interaction,
                    elementType = "scroll_max_depth",
                ),
                startDate = ts("2025-01-01T00:00:00Z"),
                endDate = null,
            ),
        )

        assertTrue(!built.sql.contains("NOT ("))
        assertEquals(listOf("Interaction", "scroll_max_depth"), built.params.drop(1).take(2))
    }

    @Test
    fun `EVENT_COUNT deduplicates retries and groups attributed events by assignment id`() {
        val built = assertNotNull(
            buildConversionCountQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(GoalMetricType.EVENT_COUNT, eventType = EventType.Interaction),
                startDate = ts("2025-01-01T00:00:00Z"),
                endDate = null,
            )
        )
        assertTrue(built.sql.contains("coalesce(client_id, CAST(id AS VARCHAR)) AS event_key"))
        assertTrue(built.sql.contains("SELECT CAST(subject_id AS VARCHAR) AS client_id, variation_key, count(DISTINCT event_key) AS cnt"))
        assertTrue(built.sql.endsWith("FROM attributed GROUP BY subject_id, variation_key"))
    }

    @Test
    fun `conversion query excludes principals before joining either identity path`() {
        val first = UUID.parse("11111111-1111-1111-1111-111111111111")
        val second = UUID.parse("22222222-2222-2222-2222-222222222222")
        val built = assertNotNull(
            buildConversionCountQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(GoalMetricType.UNIQUE_CONVERSION, eventType = EventType.Interaction),
                startDate = ts("2025-01-01T00:00:00Z"),
                endDate = null,
                excludedPrincipalIds = listOf(second, first),
                excludedInstallationIds = listOf("installation-b", "installation-a", "installation-b"),
            ),
        )

        assertTrue(built.sql.contains("principal_id NOT IN (CAST(? AS UUID), CAST(? AS UUID))"))
        assertTrue(built.sql.contains("installation_id NOT IN (?, ?)"))
        assertEquals(
            listOf(
                UUID.NIL.toString(),
                first.toString(),
                second.toString(),
                "installation-a",
                "installation-b",
            ),
            built.params.take(5),
            "principal and installation exclusions must be stable and bound before event filters",
        )
    }

    @Test
    fun `buildConversionCountQuery refuses a fully unbounded query`() {
        // No filters, no time bounds → null. The caller logs and
        // skips the goal rather than doing a full table scan.
        val built = buildConversionCountQuery(
            eventsTable = "events",
            assignmentsTable = "bosca.experimentation.assignments",
            experimentId = UUID.NIL,
            goal = goal(GoalMetricType.UNIQUE_CONVERSION),
            startDate = null,
            endDate = null,
        )
        assertNull(built, "unbounded query must return null")
    }

    @Test
    fun `buildConversionCountQuery accepts a time-only window with no filters`() {
        // A bounded time window alone is enough to avoid a
        // full-table scan.
        val built = assertNotNull(
            buildConversionCountQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(GoalMetricType.UNIQUE_CONVERSION),
                startDate = ts("2025-01-01T00:00:00Z"),
                endDate = ts("2025-01-31T00:00:00Z"),
            )
        )
        assertTrue(built.sql.contains("created >= CAST(? AS TIMESTAMP)"))
        assertTrue(built.sql.contains("created <= CAST(? AS TIMESTAMP)"))
        assertEquals(3, built.params.size,
            "the experiment id plus the two time bounds should be bound")
    }

    @Test
    fun `buildConversionCountQuery composes every filter in order`() {
        val built = assertNotNull(
            buildConversionCountQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(
                    GoalMetricType.UNIQUE_CONVERSION,
                    eventType = EventType.Completion,
                    elementType = "button",
                    elementId = "signup",
                    pagePath = "/landing",
                ),
                startDate = ts("2025-01-01T00:00:00Z"),
                endDate = ts("2025-01-15T00:00:00Z"),
            )
        )
        // Six predicates: eventType, elementType, elementId, pagePath, start, end
        assertEquals(7, built.params.size)
        assertEquals(UUID.NIL.toString(), built.params[0])
        assertEquals("Completion", built.params[1])
        assertEquals("button", built.params[2])
        assertEquals("signup", built.params[3])
        assertEquals("/landing", built.params[4])
        assertTrue(built.params[5].startsWith("2025-01-01"))
        assertTrue(built.params[6].startsWith("2025-01-15"))
    }

    @Test
    fun `activation cohort takes the first matching event across content route prefixes`() {
        val built = buildActivatedCohortQuery(
            eventsTable = "events",
            assignmentsTable = "bosca.experimentation.assignments",
            experimentId = UUID.NIL,
            activationFilter = ExperimentActivationFilter(
                eventType = EventType.Impression,
                elementType = "page",
                pagePathPrefixes = listOf("/articles/", "/studies/"),
            ),
            startDate = ts("2025-01-01T00:00:00Z"),
            endDate = ts("2025-01-31T00:00:00Z"),
        )

        assertTrue(built.sql.contains("activation_candidates AS"))
        assertTrue(built.sql.contains("min(created) AS activated_at"))
        assertTrue(built.sql.contains("starts_with(page.path, ?) OR starts_with(page.path, ?)"))
        assertTrue(built.sql.contains("candidates.created >= subjects.assigned_at"))
        assertEquals(
            listOf(UUID.NIL.toString(), "Impression", "page", "/articles/", "/studies/"),
            built.params.take(5),
        )
    }

    @Test
    fun `activation cohort composes exact event element page and item filters`() {
        val built = buildActivatedCohortQuery(
            eventsTable = "events",
            assignmentsTable = "bosca.experimentation.assignments",
            experimentId = UUID.NIL,
            activationFilter = ExperimentActivationFilter(
                eventType = EventType.Interaction,
                elementType = "scroll_depth",
                elementId = "article-depth",
                pagePath = "/articles/featured",
                pagePathPrefixes = listOf("/articles/"),
                itemExtraKey = "campaign",
                itemExtraValue = "reader",
            ),
            startDate = null,
            endDate = null,
        )

        assertFalse(built.sql.contains("NOT (type = 'Interaction'"))
        assertTrue(built.sql.contains("element.id = ?"))
        assertTrue(built.sql.contains("(page.path = ? OR starts_with(page.path, ?))"))
        assertTrue(built.sql.contains("cardinality(element.content) > 0"))
        assertTrue(built.sql.contains("json_extract_scalar(try(json_parse(element.extras)), '$.campaign') = ?"))
        assertFalse(built.sql.contains("created >= CAST(? AS TIMESTAMP)"))
        assertFalse(built.sql.contains("created <= CAST(? AS TIMESTAMP)"))
        assertEquals(
            listOf(
                UUID.NIL.toString(), "Interaction", "scroll_depth", "article-depth",
                "/articles/featured", "/articles/", "reader",
            ),
            built.params,
        )
    }

    @Test
    fun `activation cohort supports broad behavior and item key presence filters`() {
        val built = buildActivatedCohortQuery(
            eventsTable = "events",
            assignmentsTable = "bosca.experimentation.assignments",
            experimentId = UUID.NIL,
            activationFilter = ExperimentActivationFilter(itemExtraKey = "campaign"),
            startDate = null,
            endDate = null,
        )

        assertTrue(built.sql.contains("type <> 'Impression' OR element.type = 'page'"))
        assertTrue(built.sql.contains("json_extract_scalar(try(json_parse(element.extras)), '$.campaign') IS NOT NULL"))
        assertEquals(listOf(UUID.NIL.toString()), built.params)
    }

    @Test
    fun `activation cohort applies interaction and playback safety predicates`() {
        val interaction = buildActivatedCohortQuery(
            eventsTable = "events",
            assignmentsTable = "assignments",
            experimentId = UUID.NIL,
            activationFilter = ExperimentActivationFilter(
                eventType = EventType.Interaction,
                elementType = "button",
            ),
            startDate = null,
            endDate = null,
        )
        assertTrue(interaction.sql.contains("NOT coalesce(element.type, '')"))
        assertTrue(interaction.sql.contains("scroll_depth"))

        val playback = buildActivatedCohortQuery(
            eventsTable = "events",
            assignmentsTable = "assignments",
            experimentId = UUID.NIL,
            activationFilter = ExperimentActivationFilter(
                eventType = EventType.Impression,
                elementType = "media_playback",
            ),
            startDate = null,
            endDate = null,
        )
        assertFalse(playback.sql.contains("coalesce(element.type, '') <> 'media_playback'"))
    }

    @Test
    fun `activation cohort rejects an item value without a key`() {
        assertFailsWith<IllegalArgumentException> {
            buildActivatedCohortQuery(
                eventsTable = "events",
                assignmentsTable = "assignments",
                experimentId = UUID.NIL,
                activationFilter = ExperimentActivationFilter(itemExtraValue = "reader"),
                startDate = null,
                endDate = null,
            )
        }
    }

    @Test
    fun `conversion query counts only matching events strictly after activation`() {
        val built = assertNotNull(
            buildConversionCountQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(
                    GoalMetricType.UNIQUE_CONVERSION,
                    eventType = EventType.Impression,
                    elementType = "page",
                    pagePathPrefixes = listOf("/articles/", "/talks/", "/studies/"),
                ),
                startDate = ts("2025-01-01T00:00:00Z"),
                endDate = ts("2025-01-31T00:00:00Z"),
                activatedCohort = ActivatedCohort(listOf(
                    ActivatedSubject(UUID.NIL.toString(), "control", "2025-01-01 00:01:00.123456"),
                )).batches.single(),
            ),
        )

        assertTrue(built.sql.contains("INNER JOIN eligible_subjects subjects"))
        assertTrue(built.sql.contains("candidates.created >"))
        assertTrue(built.sql.contains("subjects.activated_at"))
        assertEquals(3, Regex(Regex.escape("starts_with(page.path, ?)")).findAll(built.sql).count())
        assertFalse(built.sql.contains("activation_candidates"), "outcomes must not discover new activations")
        assertTrue(built.params[1].contains("2025-01-01 00:01:00.123456"))
    }

    @Test
    fun `buildConversionCountQuery injects the events table name verbatim`() {
        val built = assertNotNull(
            buildConversionCountQuery(
                eventsTable = "prod.analytics.events_v2",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(GoalMetricType.EVENT_COUNT, eventType = EventType.Interaction),
                startDate = ts("2025-01-01T00:00:00Z"),
                endDate = null,
            )
        )
        assertTrue(built.sql.contains("FROM prod.analytics.events_v2"))
    }

    @Test
    fun `item extra presence requires content and a present JSON key`() {
        val built = assertNotNull(
            buildConversionCountQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(
                    GoalMetricType.UNIQUE_CONVERSION,
                    itemExtraKey = "recommendation_source",
                ),
                startDate = ts("2025-01-01T00:00:00Z"),
                endDate = null,
            )
        )
        assertTrue(built.sql.contains("cardinality(element.content) > 0"))
        assertTrue(
            built.sql.contains("json_extract_scalar(extras_json, '$.recommendation_source') IS NOT NULL")
        )
        assertEquals(2, built.params.size, "presence matching adds no bound value beyond experiment and time")
        assertEquals(
            1,
            "FROM candidates".toRegex().findAll(built.sql).count(),
            "item matching and diagnostics must consume the candidate stream only once",
        )
        assertTrue(built.sql.contains("GROUP BY GROUPING SETS ((subject_id, variation_key), ())"))
        assertTrue(built.sql.contains("count(DISTINCT IF(item_matches, event_key, NULL))"))
        assertTrue(built.sql.contains("grouping(subject_id) = 1 OR count(DISTINCT IF(item_matches, event_key, NULL)) > 0"))
    }

    @Test
    fun `item extra value uses exact scalar matching and composes with other filters`() {
        val built = assertNotNull(
            buildConversionCountQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(
                    GoalMetricType.EVENT_COUNT,
                    eventType = EventType.Interaction,
                    pagePath = "/discover",
                    itemExtraKey = "recommendation_strategy",
                    itemExtraValue = "two_tower",
                ),
                startDate = ts("2025-01-01T00:00:00Z"),
                endDate = null,
            )
        )
        assertTrue(built.sql.contains("cardinality(element.content) > 0"))
        assertTrue(
            built.sql.contains("json_extract_scalar(extras_json, '$.recommendation_strategy') = ?")
        )
        assertTrue(built.sql.contains("try(json_parse(raw_extras)) AS extras_json"))
        assertTrue(built.sql.contains("try_cast(extras_json AS MAP(VARCHAR, JSON)) AS extras_object"))
        assertTrue(built.sql.contains("invalid_item_extras"))
        assertTrue(built.sql.contains("missing_item_extra_values"))
        assertEquals(
            listOf(UUID.NIL.toString(), "Interaction", "/discover", "2025-01-01 00:00:00.000000", "two_tower"),
            built.params,
            "event, page, and item selectors must remain conjunctive and preserve bind order",
        )
    }

    @Test
    fun `item extra query rejects an unsafe JSON path key`() {
        assertFailsWith<IllegalArgumentException> {
            buildConversionCountQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(
                    GoalMetricType.UNIQUE_CONVERSION,
                    itemExtraKey = "recommendation-source') OR true --",
                ),
                startDate = ts("2025-01-01T00:00:00Z"),
                endDate = null,
            )
        }
    }

    @Test
    fun `conversion query rejects an item value without a key`() {
        assertFailsWith<IllegalArgumentException> {
            buildConversionCountQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(GoalMetricType.EVENT_COUNT, itemExtraValue = "value"),
                startDate = ts("2025-01-01T00:00:00Z"),
                endDate = null,
            )
        }
    }

    @Test
    fun `item selector alone bounds the conversion query`() {
        val built = assertNotNull(
            buildConversionCountQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(GoalMetricType.EVENT_COUNT, itemExtraKey = "source"),
                startDate = null,
                endDate = null,
            ),
        )
        assertTrue(built.sql.contains("cardinality(element.content) > 0"))
        assertTrue(built.sql.contains("(type <> 'Impression' OR element.type = 'page')"))
        assertTrue(built.sql.contains("coalesce(element.type, '') in ('scroll_depth', 'scroll_max_depth')"))
    }

    @Test
    fun `session duration is rejected by conversion query builders`() {
        assertFailsWith<IllegalStateException> {
            buildConversionCountQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(GoalMetricType.SESSION_DURATION, eventType = EventType.Interaction),
                startDate = null,
                endDate = null,
            )
        }
        assertFailsWith<IllegalStateException> {
            buildConversionCountQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                goal = goal(GoalMetricType.SESSION_DURATION, itemExtraKey = "source"),
                startDate = null,
                endDate = null,
            )
        }
    }

    @Test
    fun `session duration query defensively sessionizes bounded activity per subject`() {
        val built = buildSessionDurationQuery(
            eventsTable = "events",
            assignmentsTable = "bosca.experimentation.assignments",
            experimentId = UUID.NIL,
            startDate = ts("2025-01-01T00:00:00Z"),
            endDate = ts("2025-01-31T00:00:00Z"),
        )
        assertTrue(built.sql.contains("FROM bosca.experimentation.assignments"))
        assertTrue(built.sql.contains("SELECT id AS subject_id"))
        assertTrue(built.sql.contains("WHERE experiment_id = CAST(? AS UUID)"))
        assertTrue(built.sql.contains("INNER JOIN experiment_subjects"))
        assertTrue(built.sql.contains("assigned_at AT TIME ZONE 'UTC'"))
        assertTrue(built.sql.contains("events.created >= subjects.assigned_at"))
        assertTrue(built.sql.contains("context.session_id IS NOT NULL"))
        assertTrue(built.sql.contains("events.type IN ('Session', 'Interaction', 'Completion')"))
        assertTrue(built.sql.contains("events.type = 'Impression' AND events.element.type IN ('page', 'media_playback')"))
        assertFalse(built.sql.contains("events.type <> 'Impression'"))
        assertTrue(built.sql.contains("max(activity_end) OVER"))
        assertTrue(built.sql.contains("date_diff('millisecond', previous_activity_end, created) >= 300000"))
        assertTrue(built.sql.contains("ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW"))
        assertTrue(built.sql.contains("min(session_start), max(session_end)"))
        assertTrue(built.sql.contains("+ CAST(300000 AS DOUBLE) / 1000.0 AS duration_seconds"))
        assertTrue(built.sql.contains("/ 1000.0"), "session spans must be reported in seconds")
        assertTrue(built.sql.contains("PARTITION BY subject_id, variation_key, device_id, client_session_id"))
        assertTrue(built.sql.contains("GROUP BY subject_id, variation_key, device_id, client_session_id, derived_session_number"))
        assertTrue(built.sql.contains("GROUP BY subject_id, variation_key"))
        assertTrue(built.sql.contains("avg(duration_seconds) AS value"))
        assertEquals(UUID.NIL.toString(), built.params.first())
        assertEquals(4, built.params.size)
    }

    @Test
    fun `session duration includes the activation event as the session boundary`() {
        val built = buildSessionDurationQuery(
            eventsTable = "events",
            assignmentsTable = "bosca.experimentation.assignments",
            experimentId = UUID.NIL,
            startDate = ts("2025-01-01T00:00:00Z"),
            endDate = ts("2025-01-31T00:00:00Z"),
            activatedCohort = ActivatedCohort(listOf(
                ActivatedSubject(UUID.NIL.toString(), "control", "2025-01-01 00:01:00.123456"),
            )).batches.single(),
        )

        assertTrue(built.sql.contains("INNER JOIN eligible_subjects subjects"))
        assertTrue(built.sql.contains("events.created >= subjects.activated_at"))
    }

    @Test
    fun `session duration query excludes complete assignment rows for selected accounts`() {
        val first = UUID.parse("11111111-1111-1111-1111-111111111111")
        val second = UUID.parse("22222222-2222-2222-2222-222222222222")
        val built = buildSessionDurationQuery(
            eventsTable = "events",
            assignmentsTable = "bosca.experimentation.assignments",
            experimentId = UUID.NIL,
            startDate = ts("2025-01-01T00:00:00Z"),
            endDate = null,
            excludedPrincipalIds = listOf(second, first, second),
        )

        assertTrue(
            built.sql.contains(
                "principal_id IS NULL OR principal_id NOT IN (CAST(? AS UUID), CAST(? AS UUID))",
            ),
        )
        assertEquals(
            listOf(UUID.NIL.toString(), first.toString(), second.toString()),
            built.params.take(3),
        )
        assertEquals(4, built.params.size, "experiment + two accounts + lower time bound")
    }

    @Test
    fun `session duration query requires a lower time bound`() {
        assertFailsWith<IllegalArgumentException> {
            buildSessionDurationQuery(
                eventsTable = "events",
                assignmentsTable = "bosca.experimentation.assignments",
                experimentId = UUID.NIL,
                startDate = null,
                endDate = null,
            )
        }
    }

    @Test
    fun `non-UTC OffsetDateTime inputs are converted to UTC on bind`() {
        // A caller passes the start time in PST (-08:00). The
        // builder normalizes to UTC before formatting, so the
        // bound parameter is the UTC midnight of the PST date
        // plus 8 hours. 2025-01-01T00:00-08:00 = 2025-01-01T08:00Z.
        val pst = OffsetDateTime.of(
            2025, 1, 1, 0, 0, 0, 0,
            ZoneOffset.ofHours(-8),
        )
        val query = buildPerUserEventCountsQuery(
            eventsTable = "events",
            eventType = null,
            elementType = null,
            elementId = null,
            pagePath = null,
            windowStart = pst,
            windowEnd = null,
        )
        val bound = query.params.single()
        assertTrue(bound.startsWith("2025-01-01 08:00:00"),
            "PST midnight should bind as 08:00 UTC, got '$bound'")
    }

    @Test
    fun `CUPED outcome query includes the goal item selector`() {
        val query = buildPerUserEventCountsQuery(
            eventsTable = "events",
            eventType = EventType.Interaction,
            elementType = null,
            elementId = null,
            pagePath = null,
            itemExtraKey = "recommendation_source",
            itemExtraValue = "two_tower",
            windowStart = ts("2025-01-01T00:00:00Z"),
            windowEnd = ts("2025-01-31T00:00:00Z"),
        )

        assertTrue(query.sql.contains("cardinality(element.content) > 0"))
        assertTrue(query.sql.contains("json_extract_scalar(try(json_parse(element.extras)), '$.recommendation_source') = ?"))
        assertEquals("two_tower", query.params[1])
        assertTrue(query.params[2].startsWith("2025-01-01"))
        assertTrue(query.params[3].startsWith("2025-01-31"))
    }

    @Test
    fun `CUPED item presence uses the same scalar predicate as the primary metric`() {
        val query = buildPerUserEventCountsQuery(
            eventsTable = "events",
            eventType = null,
            elementType = null,
            elementId = null,
            pagePath = null,
            itemExtraKey = "recommendation_source",
            windowStart = ts("2025-01-01T00:00:00Z"),
            windowEnd = null,
        )

        assertTrue(
            query.sql.contains(
                "json_extract_scalar(try(json_parse(element.extras)), '$.recommendation_source') IS NOT NULL",
            ),
        )
        assertTrue(!query.sql.contains("json_extract(try(json_parse(element.extras))"))
    }

    @Test
    fun `CUPED query rejects malformed item selectors`() {
        assertFailsWith<IllegalArgumentException> {
            buildPerUserEventCountsQuery(
                eventsTable = "events",
                eventType = null,
                elementType = null,
                elementId = null,
                pagePath = null,
                itemExtraValue = "orphan",
                windowStart = ts("2025-01-01T00:00:00Z"),
                windowEnd = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            buildPerUserEventCountsQuery(
                eventsTable = "events",
                eventType = null,
                elementType = null,
                elementId = null,
                pagePath = null,
                itemExtraKey = "unsafe-key",
                windowStart = ts("2025-01-01T00:00:00Z"),
                windowEnd = null,
            )
        }
    }
}
