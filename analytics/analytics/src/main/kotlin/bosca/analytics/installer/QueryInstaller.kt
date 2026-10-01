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
    override val version: String = "1.0.6"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val current = service.getQueries(0, Int.MAX_VALUE).associateBy { it.key }
        listOf(
            AnalyticsQueryInput(
                key = "daily-active-users",
                name = "Daily Active Users",
                description = "Distinct users with a session start each day over the last 30 days, with daily growth. Anonymous starts are matched to a signed-in user when the session or installation supplies an unambiguous identity; recognizable bot agents are excluded.",
                query = """
                    with events_in_range as (
                        select date_trunc('day', created) as date,
                               nullif(context.user_id, '') as user_id,
                               nullif(context.device.installation_id, '') as installation_id,
                               nullif(context.session_id, '') as session_id,
                               type,
                               element.extras as extras
                        from $eventsTable
                        where created >= current_date - interval '30' day
                          and $BOT_USER_AGENT_PREDICATE
                    ),
                    session_users as (
                        select date, session_id, max(user_id) as user_id
                        from events_in_range
                        where session_id is not null and user_id is not null
                        group by date, session_id
                        having count(distinct user_id) = 1
                    ),
                    installation_users as (
                        select date, installation_id, max(user_id) as user_id
                        from events_in_range
                        where installation_id is not null and user_id is not null
                        group by date, installation_id
                        having count(distinct user_id) = 1
                    ),
                    identified_starts as (
                        select s.date,
                               s.installation_id,
                               coalesce(s.user_id, su.user_id, iu.user_id) as resolved_user_id
                        from events_in_range s
                        left join session_users su on su.date = s.date and su.session_id = s.session_id
                        left join installation_users iu on iu.date = s.date and iu.installation_id = s.installation_id
                        where s.type = 'Session'
                          and json_extract_scalar(json_parse(s.extras), '$.start') = 'true'
                    ),
                    daily_users as (
                        select date,
                               count(distinct case
                                   when resolved_user_id is not null then 'user:' || resolved_user_id
                                   when installation_id is not null then 'installation:' || installation_id
                               end) as value
                        from identified_starts
                        group by date
                    )
                    select date,
                           value,
                           lag(value) over (order by date) as previous_value,
                        round(cast((value - lag(value) over (order by date)) as double) / nullif(lag(value) over(order by date), 0) * 100) as change
                    from daily_users
                    order by date desc
                """.trimIndent(),
                parameters = listOf(),
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
                description = "Distinct session starts per day, excluding recognizable bot agents.",
                query = """
                    select date(created) as date,
                           count(distinct nullif(context.session_id, '')) as value
                    from $eventsTable
                    where $SESSION_START_PREDICATE
                      and $BOT_USER_AGENT_PREDICATE
                    group by date(created)
                    order by date(created)
                """.trimIndent(),
                parameters = listOf(),
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
                key = "total-organizations",
                name = "Total Organizations",
                description = "Total Organizations",
                query = """
                    select count(distinct organizations.id) as value from $postgresCatalog."public".organizations as organizations
                    inner join $postgresCatalog."public".organization_members as organization_members on organizations.id = organization_members.organization_id
                    inner join $postgresCatalog."public".principals as principals on organization_members.principal_id = principals.id
                    where lower(name) not like '%test%' and lower(name) not like '%admin%'
                      and verification_token is null and verified = true
                """.trimIndent()
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
                """.trimIndent()
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
