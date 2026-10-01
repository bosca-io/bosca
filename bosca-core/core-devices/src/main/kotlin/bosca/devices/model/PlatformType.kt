package bosca.devices.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Identifies the operating system or runtime environment of a registered device.
 *
 * Used to determine which push notification transport (FCM, APNs, Web Push)
 * should be used when delivering messages to the device.
 */
@DbMapper(PlatformTypeMapper::class)
@Serializable
enum class PlatformType {
    IOS,
    ANDROID,
    WEB,
    DESKTOP
}

object PlatformTypeMapper : EnumMapper<PlatformType>({ PlatformType.valueOf(it.uppercase()) })
