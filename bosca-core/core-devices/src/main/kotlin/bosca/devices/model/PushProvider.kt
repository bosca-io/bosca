package bosca.devices.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/** Identifies the service that issued and accepts a push token. */
@DbMapper(PushProviderMapper::class)
@Serializable
enum class PushProvider {
    FCM,
    APNS,
}

object PushProviderMapper : EnumMapper<PushProvider>({ PushProvider.valueOf(it.uppercase()) })
