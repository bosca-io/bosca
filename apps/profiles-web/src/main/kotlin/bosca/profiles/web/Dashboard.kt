package bosca.profiles.web

import bosca.bml.graphql.execute
import bosca.bml.render.client
import bosca.profiles.web.graphql.Dashboard

suspend fun dashboard(): DashboardPage {
    val data = client().execute(Dashboard, Unit)
    val profiles = data.profiles.current.orEmpty()
    val profile = profiles.firstOrNull { it.isPrimary } ?: profiles.firstOrNull()
    val principal = data.security.principals.current
    return DashboardPage(
        name = profile?.name ?: "Your account",
        relationshipCount = profile?.relationships?.size ?: 0,
        deviceCount = data.devices.devices.size,
        verified = principal.verified,
        lastLogin = principal.lastLogin ?: "No login recorded",
    )
}
