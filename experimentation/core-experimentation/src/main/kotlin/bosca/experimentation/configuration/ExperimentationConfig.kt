package bosca.experimentation.configuration

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.slf4j.LoggerFactory

/**
 * Configuration for the experimentation module's integration with the
 * analytics data warehouse. Controls which Trino table is queried when
 * aggregating conversion events and installing event-backed analytics queries.
 *
 * Access the table name via [safeEventsTable], which holds the trimmed and
 * validated value. The raw [eventsTable] constructor parameter is retained
 * for serialization compatibility.
 */
@Serializable
data class ExperimentationConfig(
    val eventsTable: String = DEFAULT_EVENTS_TABLE,
    val assignmentsTable: String = DEFAULT_ASSIGNMENTS_TABLE,
    val postgresCatalog: String = DEFAULT_POSTGRES_CATALOG,
) {
    /**
     * Normalized table reference safe for SQL interpolation. An invalid value is logged and replaced by
     * [DEFAULT_EVENTS_TABLE] so the misconfiguration surfaces as failing queries instead of preventing startup.
     */
    @Transient
    val safeEventsTable: String = safeIdentifier(eventsTable, SAFE_TABLE_NAME, DEFAULT_EVENTS_TABLE, "eventsTable")

    /** Normalized PostgreSQL-catalog assignment table used for warehouse membership joins, validated like [safeEventsTable]. */
    @Transient
    val safeAssignmentsTable: String =
        safeIdentifier(assignmentsTable, SAFE_TABLE_NAME, DEFAULT_ASSIGNMENTS_TABLE, "assignmentsTable")

    /** Trino catalog exposing this installation's operational PostgreSQL database, validated like [safeEventsTable]. */
    @Transient
    val safePostgresCatalog: String =
        safeIdentifier(postgresCatalog, SAFE_CATALOG_NAME, DEFAULT_POSTGRES_CATALOG, "postgresCatalog")

    init {
        // Log the resolved value at startup so a misconfigured
        // `EXPERIMENTATION_EVENTS_TABLE` env override is visible in the
        // boot log instead of producing surprising "no events" results
        // from the aggregation job hours later.
        log.info("ExperimentationConfig.eventsTable resolved to '{}'", safeEventsTable)
        log.info("ExperimentationConfig.assignmentsTable resolved to '{}'", safeAssignmentsTable)
        log.info("ExperimentationConfig.postgresCatalog resolved to '{}'", safePostgresCatalog)
    }

    companion object {
        const val DEFAULT_EVENTS_TABLE = "warehouse.bosca.events"
        const val DEFAULT_ASSIGNMENTS_TABLE = "bosca.experimentation.assignments"
        const val DEFAULT_POSTGRES_CATALOG = "bosca"

        private val log = LoggerFactory.getLogger(ExperimentationConfig::class.java)

        /**
         * Matches a fully-qualified Trino table reference consisting of one to three
         * dot-separated SQL identifiers. Each segment allows alphanumerics and underscores.
         */
        private val SAFE_TABLE_NAME = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*){0,2}$""")
        private val SAFE_CATALOG_NAME = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*$""")

        private fun safeIdentifier(value: String, pattern: Regex, default: String, setting: String): String {
            val trimmed = value.trim()
            if (pattern.matches(trimmed)) return trimmed
            log.error("Ignoring invalid experimentation.{} '{}'; using '{}'", setting, value, default)
            return default
        }
    }
}
