package bosca.analytics.configuration

import bosca.db.migrations.Migration

/**
 * Flyway migration definition for the `analytics` schema. The analytics tables
 * themselves live in the `public` schema (managed by the core migration chain);
 * this chain exists for analytics-owned data that depends on other modules'
 * schemas — currently the scheduled job that drives the query result refresh
 * sweep, which must run after the `scheduler` schema has been created.
 */
class AnalyticsMigration : Migration {

    override val schema: String = "analytics"

    override val dependsOn: List<String> = listOf("scheduler")

    override val resources: List<String> = listOf(
        "V1__scheduled_query_refresh_sweep.sql",
        "V2__analytics_script_bindings.sql",
    )
}
