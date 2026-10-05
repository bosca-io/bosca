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

/**
 * Installs raw-event analytics queries without overriding queries that already exist.
 *
 * Unlike [QueryInstaller], this installer only creates queries that are missing and never
 * updates existing ones, preserving any user-customised query definitions.
 */
class RawEventsQueryInstaller(
    private val service: AnalyticsQueryService,
    private val permissions: SecurityService,
    eventsTable: String = DEFAULT_EVENTS_TABLE,
) : PackageInstaller {
    private val eventsTable = safeEventsTable(eventsTable)
    override val version: String = "1.0.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val current = service.getQueries(0, Int.MAX_VALUE).associateBy { it.key }
        listOf(
            AnalyticsQueryInput(
                key = "raw-events",
                name = "Raw Analytics Events",
                description = "Browse raw analytics events within a date range for introspection and debugging.",
                query = """
                    select
                        cast(id as varchar) as id,
                        client_id,
                        type,
                        created,
                        sent,
                        received,
                        cast(context as json) as context,
                        cast(element as json) as element,
                        cast(error as json) as error
                    from $eventsTable
                    where created >= :start_date
                      and created <= :end_date
                    order by created desc
                    offset :offset rows
                    fetch next :page_size rows only
                """.trimIndent(),
                parameters = listOf(
                    AnalyticsQueryParameterInput(
                        parameter = "start_date",
                        name = "Start Date",
                        description = "Filter events created on or after this date",
                        type = QueryParameterType.DATETIME,
                        arrayType = null,
                        defaultValue = JsonObject(
                            mapOf("now" to JsonPrimitive(true), "nowDayOffset" to JsonPrimitive(-7))
                        ),
                        required = true
                    ),
                    AnalyticsQueryParameterInput(
                        parameter = "end_date",
                        name = "End Date",
                        description = "Filter events created on or before this date",
                        type = QueryParameterType.DATETIME,
                        arrayType = null,
                        defaultValue = JsonObject(
                            mapOf("now" to JsonPrimitive(true))
                        ),
                        required = true
                    ),
                    AnalyticsQueryParameterInput(
                        parameter = "offset",
                        name = "Offset",
                        description = "Number of rows to skip for pagination",
                        type = QueryParameterType.INTEGER,
                        arrayType = null,
                        defaultValue = JsonPrimitive(0),
                        required = true
                    ),
                    AnalyticsQueryParameterInput(
                        parameter = "page_size",
                        name = "Page Size",
                        description = "Number of rows to return per page",
                        type = QueryParameterType.INTEGER,
                        arrayType = null,
                        defaultValue = JsonPrimitive(50),
                        required = true
                    )
                ),
                configuration = JsonObject(emptyMap())
            )
        ).forEach {
            if (!current.containsKey(it.key)) {
                val group = permissions.getGroupByName("administrators", GroupType.SYSTEM) ?: error("No administrators group found")
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
            }
        }
    }
}
