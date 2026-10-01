package bosca.devices.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A push notification registration token associated with a specific device.
 *
 * Tokens are provided by the device's push notification service (FCM, APNs)
 * and are used by the server to target push messages to that device.
 * Tokens may become invalid over time and should be removed when a push
 * provider reports them as unregistered.
 */
@Serializable
data class PushToken(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("device_id")
    val deviceId: UUID,
    val token: String,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    val provider: PushProvider,
) {
    /**
     * Constructs a legacy FCM token. This preserves the published constructor while callers
     * migrate to the provider-aware primary constructor.
     */
    constructor(
        id: UUID = UUID.NIL,
        deviceId: UUID,
        token: String,
        created: OffsetDateTime = OffsetDateTime.now(),
    ) : this(id, deviceId, token, created, PushProvider.FCM)
}
