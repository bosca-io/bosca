package bosca.content.metadata.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(GuideTypeMapper::class)
@Serializable
enum class GuideType {
    LINEAR,
    LINEAR_PROGRESS,
    CALENDAR,
    CALENDAR_PROGRESS
}

object GuideTypeMapper : EnumMapper<GuideType>({ GuideType.valueOf(it.uppercase()) })
