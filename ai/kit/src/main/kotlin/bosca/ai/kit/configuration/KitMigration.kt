package bosca.ai.kit.configuration

import bosca.db.migrations.Migration

/**
 * Flyway migration for the `kit` schema — the checkpoint index (`kit.checkpoint`) backing
 * `KitSessionServiceImpl`. Provided to the platform migration runner via [Configuration].
 */
class KitMigration : Migration {

    override val schema: String = "kit"

    override val resources: List<String> = listOf(
        "V1__kit_initial.sql",
    )
}
