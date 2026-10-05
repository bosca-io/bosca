package bosca.db.migrations

/**
 * Executes database migrations using the provided [Migration] definitions.
 *
 * Implementations typically delegate to Flyway, applying each migration's SQL scripts
 * against the appropriate database schema.
 */
interface Migrations {

    /**
     * Runs all pending database migrations defined in the given list.
     *
     * @param migrations the migration definitions to apply, each targeting a specific schema
     */
    suspend fun migrate(migrations: List<Migration>)
}