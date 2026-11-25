package bosca.ai.kit.configuration

import bosca.db.migrations.Migration

/**
 * Flyway migration for the `kit` schema (runtime, policy, context, archive and delegation state).
 * Provided to the platform migration runner via [Configuration].
 */
class KitMigration : Migration {

    override val schema: String = "kit"

    override val resources: List<String> = listOf(
        "V1__kit_initial.sql",
        "V2__kit_runtime.sql",
        "V3__kit_cancellation.sql",
        "V4__kit_policy.sql",
        "V5__kit_context.sql",
        "V6__kit_turn_deadline.sql",
        "V7__kit_observability.sql",
        "V8__kit_archive_retention.sql",
        "V9__kit_runtime_instance.sql",
        "V10__kit_swarm_dispatch.sql",
        "V11__kit_delegation.sql",
        "V12__kit_runtime_recovery.sql",
    )
}
