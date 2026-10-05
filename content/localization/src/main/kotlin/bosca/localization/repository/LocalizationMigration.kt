package bosca.localization.repository

import bosca.db.migrations.Migration

/**
 * Flyway migration descriptor for the dedicated `localization` PostgreSQL schema.
 *
 * Every SQL file registered here is discovered by Bosca's custom Flyway `ResourceProvider`;
 * placing a file in `src/main/resources/db/migrations` alone is not sufficient.
 */
class LocalizationMigration : Migration {

    override val schema: String = "localization"

    override val resources: List<String> = listOf(
        "V1__localization.sql",
        "V2__history_table_name_check.sql",
        "V3__project_languages_and_formats.sql",
        "V4__string_metadata.sql"
    )
}
