package bosca.db.migrations

/**
 * Describes a set of database migration resources to apply under a specific schema.
 *
 * Implementations group Flyway migration script resource paths by their target schema,
 * allowing the [Migrations] executor to apply them in the correct order and context.
 */
interface Migration {

    /** The database schema name these migrations should be applied to. */
    val schema: String
    /** The classpath resource locations containing the Flyway migration SQL scripts. */
    val resources: List<String>
    /** Schema names that must be fully migrated before this migration runs. */
    val dependsOn: List<String> get() = emptyList()
}