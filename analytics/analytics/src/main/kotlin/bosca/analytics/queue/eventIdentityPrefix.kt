package bosca.analytics.queue

import bosca.analytics.model.EventContext

internal fun eventIdentityPrefix(context: EventContext?): String? {
    if (context == null || context.device.installationId.isBlank()) return null
    val app = context.appId
    val installation = context.device.installationId
    val user = context.userId
    return "${app.length}:$app:${installation.length}:$installation:${user?.length ?: -1}:${user.orEmpty()}:"
}
