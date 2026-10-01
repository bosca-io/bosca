package bosca.scripting.repository

import bosca.db.migrations.Migration

class ScriptingMigration : Migration {

    override val schema: String = "scripting"

    override val resources: List<String> = listOf(
        "V1__scripts.sql",
        "V2__trigger_bindings_timestamps.sql",
        "V3__agent_tool_scripts_fk.sql",
        "V4__ephemeral_soft_delete.sql",
        "V5__script_permissions.sql",
        "V6__compiled_scripts.sql",
        "V7__platform_event_bindings.sql",
        "V8__drop_trigger_bindings.sql"
    )
}
