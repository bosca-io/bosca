package bosca.profiles.web

import bosca.bml.graphql.execute
import bosca.bml.render.client
import bosca.profiles.web.graphql.CommunicationPreferences

suspend fun communicationsModel(): CommunicationsModel {
    val data = client().execute(CommunicationPreferences, Unit).communications
    val preferences = data.myNotificationPreferences.associate {
        (it.channel.toString() to it.type) to it.optedOut
    }
    val settings = data.myNotificationSettings
    return CommunicationsModel(
        rows = data.notificationTypes.filterNot { it.hidden }.sortedBy { it.displayOrder }.map {
            NotificationRow(
                key = it.key,
                name = it.name,
                description = it.description.orEmpty(),
                optional = it.optional,
                emailOptedOut = preferences["EMAIL" to it.key] == true,
                pushOptedOut = preferences["PUSH" to it.key] == true,
            )
        },
        timeZone = settings?.timeZone.orEmpty(),
        start = settings?.dndStartLocal.orEmpty(),
        end = settings?.dndEndLocal.orEmpty(),
    )
}
