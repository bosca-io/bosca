package bosca.devices.configuration

import bosca.db.migrations.Migration

/**
 * Flyway migration definition for the devices schema, which manages device registrations and push tokens.
 */
class DevicesMigration : Migration {

    override val schema: String = "devices"

    override val resources: List<String> = listOf(
        "V1__devices.sql",
        "V2__unique_push_tokens.sql",
        "V3__nullable_device_principal.sql",
    )
}
