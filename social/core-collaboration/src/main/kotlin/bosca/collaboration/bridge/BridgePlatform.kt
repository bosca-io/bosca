package bosca.collaboration.bridge

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * The external messaging platform a bridge binding connects to.
 */
@DbMapper(BridgePlatformMapper::class)
@Serializable
enum class BridgePlatform {
    SLACK,
    TEAMS
}

object BridgePlatformMapper : EnumMapper<BridgePlatform>({ BridgePlatform.valueOf(it.uppercase()) })
