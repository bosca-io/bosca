package bosca.profiles.web

import bosca.bml.graphql.execute
import bosca.bml.render.client
import bosca.bml.render.currentRenderContext
import bosca.profiles.web.graphql.RegisteredDevices

suspend fun devicesModel(): DevicesModel {
    val data = client().execute(RegisteredDevices, Unit)
    return DevicesModel(
        devices = data.devices.devices.map {
            DeviceRow(
                it.id,
                it.platform.toString(),
                it.created,
                it.lastCheckIn,
                it.pushTokens.size,
            )
        },
        tokens = data.security.apiTokens.my.map {
            ApiTokenRow(
                id = it.id,
                name = it.name,
                description = it.description.orEmpty(),
                prefix = it.tokenPrefix,
                scopes = it.scopes.orEmpty().joinToString(", "),
                expiresAt = it.expiresAt.orEmpty(),
                lastUsedAt = it.lastUsedAt.orEmpty(),
                lastUsedIp = it.lastUsedIp.orEmpty(),
                revokedAt = it.revokedAt.orEmpty(),
                active = it.active,
            )
        },
        tab = currentRenderContext().query["tab"].takeIf { it == "tokens" } ?: "devices",
    )
}
