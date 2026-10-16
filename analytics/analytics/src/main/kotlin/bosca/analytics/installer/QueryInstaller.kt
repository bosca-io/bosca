package bosca.analytics.installer

import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.model.AnalyticsQueryParameterInput
import bosca.analytics.model.QueryParameterType
import bosca.analytics.service.AnalyticsQueryService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.SecurityService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

private const val BOT_USER_AGENT_PREDICATE =
    "not regexp_like(lower(coalesce(context.browser.agent, '')), " +
        "'bot|crawl|spider|headless|phantomjs|googleother|google-inspectiontool|" +
        "mediapartners-google|microsoftpreview|bingvideopreview|facebookexternalhit|chatgpt-user')"
private const val SESSION_START_PREDICATE =
    "type = 'Session' and json_extract_scalar(json_parse(element.extras), '$.start') = 'true'"

/**
 * Matches desktop Chrome agents that report a full build number. Desktop Chrome reports a reduced
 * `X.0.0.0` version, so a full build number on desktop identifies a scraper presenting a browser agent.
 */
private const val DESKTOP_CRAWLER_AGENT =
    """(regexp_like(coalesce(context.browser.agent, ''), 'Chrome/[0-9]+\.0\.[1-9]')""" +
        " and regexp_like(coalesce(context.browser.agent, ''), 'Windows NT|Macintosh|X11')" +
        " and not regexp_like(coalesce(context.browser.agent, ''), 'Android'))"

/** Inclusive `startDate`/`endDate` DATE parameters shared by the activity metric queries. */
private fun dateRangeParameters(daysBack: Int) = listOf(
    AnalyticsQueryParameterInput(
        parameter = "startDate",
        name = "Start Date",
        description = "First day included",
        type = QueryParameterType.DATE,
        arrayType = null,
        defaultValue = JsonObject(mapOf("now" to JsonPrimitive(true), "nowDayOffset" to JsonPrimitive(daysBack))),
        required = true,
    ),
    AnalyticsQueryParameterInput(
        parameter = "endDate",
        name = "End Date",
        description = "Last day included",
        type = QueryParameterType.DATE,
        arrayType = null,
        defaultValue = JsonObject(mapOf("now" to JsonPrimitive(true))),
        required = true,
    ),
)

/** Twelve hours, the cadence the metric queries are refreshed on. */
private const val METRIC_REFRESH_INTERVAL_SECONDS = 43_200

internal const val DEFAULT_EVENTS_TABLE = "warehouse.bosca.events"
internal const val DEFAULT_POSTGRES_CATALOG = "bosca"
private val TABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,2}")
private val CATALOG_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val log = LoggerFactory.getLogger("bosca.analytics.installer.QueryInstaller")

/**
 * Returns [value] when it is safe to interpolate into SQL. An invalid setting is logged and replaced with
 * [fallback] so a misconfiguration surfaces as failing queries instead of preventing startup.
 */
private fun safeIdentifier(value: String, pattern: Regex, fallback: String, setting: String): String {
    val trimmed = value.trim()
    if (pattern.matches(trimmed)) return trimmed
    log.error("Ignoring invalid {} '{}'; using '{}'", setting, value, fallback)
    return fallback
}

internal fun safeEventsTable(table: String): String =
    safeIdentifier(table, TABLE_NAME, DEFAULT_EVENTS_TABLE, "experimentation.eventsTable")

internal fun safePostgresCatalog(catalog: String): String =
    safeIdentifier(catalog, CATALOG_NAME, DEFAULT_POSTGRES_CATALOG, "experimentation.postgresCatalog")

class QueryInstaller(
    private val service: AnalyticsQueryService,
    private val permissions: SecurityService,
    eventsTable: String = DEFAULT_EVENTS_TABLE,
    postgresCatalog: String = DEFAULT_POSTGRES_CATALOG,
) : PackageInstaller {
    private val eventsTable = safeEventsTable(eventsTable)
    private val postgresCatalog = safePostgresCatalog(postgresCatalog)
    override val version: String = "1.0.7"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val current = service.getQueries(0, Int.MAX_VALUE).associateBy { it.key }
        listOf(
            AnalyticsQueryInput(
                key = "daily-active-users",
                name = "Daily Active Users",
                description = "Distinct users with a session start each day, with daily growth. Signed-out starts count as the installation's signed-in user when exactly one user signed in on it that day; recognizable bots and desktop crawlers are excluded.",
                query = """
                    with events as (
                        select date_trunc('day', created) as date,
                               nullif(context.user_id, '') as user_id,
                               nullif(context.device.installation_id, '') as installation_id,
                               $SESSION_START_PREDICATE as is_start,
                               $DESKTOP_CRAWLER_AGENT as crawler
                        from $eventsTable
                        where created >= :startDate
                          and created < :endDate + interval '1' day
                          and $BOT_USER_AGENT_PREDICATE
                    ),
                    installation_users as (
                        select date, installation_id, arbitrary(user_id) as user_id
                        from events
                        where installation_id is not null and user_id is not null
                        group by 1, 2
                        having count(distinct user_id) = 1
                    ),
                    daily_users as (
                        select s.date,
                               count(distinct coalesce(s.user_id, iu.user_id, s.installation_id)) as value
                        from events s
                        left join installation_users iu on iu.date = s.date and iu.installation_id = s.installation_id
                        where s.is_start and not s.crawler
                        group by 1
                    )
                    select date,
                           value,
                           previous_value,
                           round(cast(value - previous_value as double) / nullif(previous_value, 0) * 100) as change
                    from (select *, lag(value) over (order by date) as previous_value from daily_users)
                    order by date
                """.trimIndent(),
                parameters = dateRangeParameters(-30),
                refreshIntervalSeconds = METRIC_REFRESH_INTERVAL_SECONDS,
                configuration = JsonObject(
                    mapOf(
                        "jsOptions" to JsonPrimitive(
                            """
                        {
                            title: { text: 'Daily Active Users' },
                            legend: { data: ['Daily Active Users'] },
                            xAxis: {
                                data: data.records.map(r => dateFormatter.format(new Date(r.date)))
                            },
                            yAxis: { name: 'Users' },
                            series: [
                                {
                                    name: 'Daily Active Users',
                                    type: 'bar',
                                    data: data.records.map(r => r.value)
                                }
                            ]
                        }
                    """.trimIndent()
                        )
                    )
                )
            ),
            AnalyticsQueryInput(
                key = "unique-sessions-per-day",
                name = "Unique Sessions Per Day",
                description = "Distinct sessions with a session start each day, excluding recognizable bots and desktop crawlers. Sessions end after 5 minutes without activity, so counts run higher than tools that use a 30-minute timeout.",
                query = """
                    select date(created) as date,
                           count(distinct nullif(context.session_id, '')) as value
                    from $eventsTable
                    where created >= :startDate
                      and created < :endDate + interval '1' day
                      and $SESSION_START_PREDICATE
                      and $BOT_USER_AGENT_PREDICATE
                      and not $DESKTOP_CRAWLER_AGENT
                    group by date(created)
                    order by date(created)
                """.trimIndent(),
                parameters = dateRangeParameters(-30),
                refreshIntervalSeconds = METRIC_REFRESH_INTERVAL_SECONDS,
                configuration = JsonObject(
                    mapOf(
                        "jsOptions" to JsonPrimitive(
                            """
                        {
                            title: { text: 'Unique Sessions Per Day' },
                            legend: { data: ['Unique Sessions Per Day'] },
                            xAxis: {
                                data: data.records.map(r => dateFormatter.format(new Date(r.date)))
                            },
                            yAxis: { name: 'Sessions' },
                            series: [
                                {
                                    name: 'Unique Sessions Per Day',
                                    type: 'bar',
                                    data: data.records.map(r => r.value)
                                }
                            ]
                        }
                    """.trimIndent()
                        )
                    )
                )
            ),
            AnalyticsQueryInput(
                key = "monthly-users-and-engagement",
                name = "Monthly Users and Engagement",
                description = "Monthly active users and average engagement time per active user, for comparison with web analytics tools. Users follow the Daily Active Users definition with signed-out activity linked within each month. Engagement is an estimate from the time between consecutive events in each session, capped at the 5-minute session timeout, with audio and video playback counted as activity; it is comparable only across periods with unchanged event instrumentation. Use whole-month date ranges.",
                query = """
                    with events as (
                        select date_trunc('month', created) as month,
                               created,
                               nullif(context.user_id, '') as user_id,
                               nullif(context.device.installation_id, '') as installation_id,
                               nullif(context.session_id, '') as session_id,
                               $SESSION_START_PREDICATE as is_start
                        from $eventsTable
                        where created >= :startDate
                          and created < :endDate + interval '1' day
                          and type not in ('Heartbeat', 'Installation', 'Assignment')
                          and $BOT_USER_AGENT_PREDICATE
                          and not $DESKTOP_CRAWLER_AGENT
                    ),
                    installation_users as (
                        select month, installation_id, arbitrary(user_id) as user_id
                        from events
                        where installation_id is not null and user_id is not null
                        group by 1, 2
                        having count(distinct user_id) = 1
                    ),
                    gaps as (
                        select e.month,
                               e.is_start,
                               coalesce(e.user_id, iu.user_id, e.installation_id) as identity,
                               least(coalesce(date_diff('millisecond', e.created,
                                   lead(e.created) over (partition by e.installation_id, e.session_id order by e.created)), 0), 300000) as engaged_ms
                        from events e
                        left join installation_users iu on iu.month = e.month and iu.installation_id = e.installation_id
                        where e.installation_id is not null and e.session_id is not null
                    ),
                    per_user as (
                        select month, identity, bool_or(is_start) as active, sum(engaged_ms) as engaged_ms
                        from gaps
                        group by 1, 2
                    )
                    select month,
                           count(*) as active_users,
                           round(cast(sum(engaged_ms) as double) / 1000 / count(*)) as avg_engagement_seconds,
                           round(cast(sum(engaged_ms) as double) / 60000 / count(*), 2) as avg_engagement_minutes
                    from per_user
                    where active
                    group by 1
                    order by 1
                """.trimIndent(),
                parameters = dateRangeParameters(-180),
                refreshIntervalSeconds = METRIC_REFRESH_INTERVAL_SECONDS,
                configuration = JsonObject(
                    mapOf(
                        "jsOptions" to JsonPrimitive(
                            """
                        {
                            title: { text: 'Monthly Users and Engagement' },
                            tooltip: { trigger: 'axis' },
                            legend: { data: ['Active Users', 'Avg Engagement (min)'] },
                            xAxis: {
                                data: data.records.map(r => dateFormatter.format(new Date(r.month)))
                            },
                            yAxis: [
                                { type: 'value', name: 'Users' },
                                { type: 'value', name: 'Minutes' }
                            ],
                            series: [
                                {
                                    name: 'Active Users',
                                    type: 'bar',
                                    data: data.records.map(r => r.active_users)
                                },
                                {
                                    name: 'Avg Engagement (min)',
                                    type: 'line',
                                    yAxisIndex: 1,
                                    data: data.records.map(r => r.avg_engagement_minutes)
                                }
                            ]
                        }
                    """.trimIndent()
                        )
                    )
                )
            ),
            AnalyticsQueryInput(
                key = "total-organizations",
                name = "Total Organizations",
                description = "Total Organizations",
                query = """
                    select count(distinct organizations.id) as value from $postgresCatalog."public".organizations as organizations
                    inner join $postgresCatalog."public".organization_members as organization_members on organizations.id = organization_members.organization_id
                    inner join $postgresCatalog."public".principals as principals on organization_members.principal_id = principals.id
                    where lower(name) not like '%test%' and lower(name) not like '%admin%'
                      and verification_token is null and verified = true
                """.trimIndent(),
                refreshIntervalSeconds = METRIC_REFRESH_INTERVAL_SECONDS
            ),
            AnalyticsQueryInput(
                key = "total-profiles",
                name = "Total Profiles",
                description = "Total Profiles",
                query = """
                    select count(*) as value from $postgresCatalog."public".profiles as profiles
                     inner join $postgresCatalog."public".principals as principals on profiles.principal = principals.id
                    where lower(name) not like '%test%' and lower(name) not like '%admin%'
                    and verification_token is null and verified = true
                """.trimIndent(),
                refreshIntervalSeconds = METRIC_REFRESH_INTERVAL_SECONDS
            ),
            AnalyticsQueryInput(
                key = "top-50-page-impressions",
                name = "Top 50 Page Impressions Over Last 30 Days",
                description = "Top 50 daily page-impression counts over the last 30 days, deduplicated by event ID and excluding recognizable bot agents.",
                query = """
                    with page_impressions as (
                        select date(created) as date,
                               regexp_replace(
                                   regexp_replace(
                                       regexp_replace(element.id, '(?i)([?&])utm_[^=]+=[^&]*', '$1'),
                                       '\?&', '?'
                                   ),
                                   '[?&]+$', ''
                               ) as page_id,
                               coalesce(nullif(client_id, ''), cast(id as varchar)) as impression_id
                        from $eventsTable
                        where created >= current_date - interval '30' day
                          and type = 'Impression'
                          and element.type = 'page'
                          and $BOT_USER_AGENT_PREDICATE
                    )
                    select date, page_id, count(distinct impression_id) as impressions
                    from page_impressions
                    group by date, page_id
                    order by date desc, impressions desc
                    limit 50
                """.trimIndent(),
                parameters = listOf(),
                configuration = JsonObject(
                    mapOf(
                        "jsOptions" to JsonPrimitive(
                            """
                        {
                            title: { text: 'Top 50 Page Impressions Over Last 30 Days' },
                            tooltip: {
                                trigger: 'axis',
                                axisPointer: { type: 'shadow' }
                            },
                            legend: {
                                type: 'scroll',
                                bottom: 0
                            },
                            xAxis: {
                                type: 'category',
                                data: [...new Set(data.records.map(r => dateFormatter.format(new Date(r.date))))]
                            },
                            yAxis: {
                                type: 'value',
                                name: 'Impressions'
                            },
                            series: [...new Set(data.records.map(r => r.page_id))].map(pageId => ({
                                name: pageId,
                                type: 'line',
                                stack: 'total',
                                areaStyle: {},
                                emphasis: { focus: 'series' },
                                data: [...new Set(data.records.map(r => dateFormatter.format(new Date(r.date))))].map(date => {
                                    const record = data.records.find(r => 
                                        dateFormatter.format(new Date(r.date)) === date && r.page_id === pageId
                                    );
                                    return record ? record.impressions : 0;
                                })
                            }))
                        }
                    """.trimIndent()
                        )
                    )
                )
            ),
            AnalyticsQueryInput(
                key = "store-release-telemetry",
                name = "Store Release Telemetry",
                description = "Store vitals, rating/review, and state observations for one application version.",
                query = """
                    select
                        created,
                        element.type as telemetry_type,
                        cast(json_parse(element.extras) as varchar) as payload
                    from $eventsTable
                    where element.type in ('store.vitals', 'store.reviews', 'store.state')
                      and json_extract_scalar(json_parse(element.extras), '$.applicationId') = :application_id
                      and json_extract_scalar(json_parse(element.extras), '$.appVersion') = :app_version
                    order by created desc
                    limit 500
                """.trimIndent(),
                parameters = listOf(
                    AnalyticsQueryParameterInput(
                        parameter = "application_id",
                        name = "Application id",
                        description = "Play package name or App Store bundle id",
                        type = QueryParameterType.STRING,
                        arrayType = null,
                        required = true,
                    ),
                    AnalyticsQueryParameterInput(
                        parameter = "app_version",
                        name = "App version",
                        description = "WorkOps version mapped to the store marketing version",
                        type = QueryParameterType.STRING,
                        arrayType = null,
                        required = true,
                    ),
                ),
            ),
        ).forEach {
            val group = permissions.getGroupByName("administrators", GroupType.SYSTEM) ?: error("No administrators group found")
            if (!current.containsKey(it.key)) {
                val query = service.addQuery(it)
                service.addPermission(
                    PermissionInput(
                        entityId = query.id,
                        groupId = group.id,
                        action = PermissionAction.VIEW
                    )
                )
                service.addPermission(
                    PermissionInput(
                        entityId = query.id,
                        groupId = group.id,
                        action = PermissionAction.EXECUTE
                    )
                )
            } else {
                service.editQuery(it.copy(id = current.getValue(it.key).id))
            }
        }
    }
}
