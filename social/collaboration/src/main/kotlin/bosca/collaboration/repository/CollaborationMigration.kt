package bosca.collaboration.repository

import bosca.db.migrations.Migration

/**
 * Flyway migration registry for the collaboration schema, managing
 * mention, bridge, and federation table evolution.
 */
class CollaborationMigration : Migration {

    override val schema: String = "collaboration"

    override val dependsOn: List<String> = listOf("chat")

    override val resources: List<String> = listOf(
        "V1__collaboration_schema.sql",
        "V2__bridge_tables.sql",
        "V3__federation_tables.sql",
    )
}
