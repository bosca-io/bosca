package bosca.gateway.migration

import bosca.db.migrations.Migration

class GatewayMigration : Migration {
    override val schema: String = "gateway"
    override val resources: List<String> = listOf(
        "V1__gateway_initial.sql",
        "V2__gateway_route_hosts.sql",
        "V3__gateway_route_host_unique.sql",
    )
}
