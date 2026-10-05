package bosca.attributes

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(AttributeUiTypeMapper::class)
@Serializable
enum class AttributeUiType {
    INPUT,
    TEXTAREA,
    IMAGE,
    PROFILE,
    FILE,
    METADATA,
    COLLECTION
}

object AttributeUiTypeMapper : EnumMapper<AttributeUiType>({ AttributeUiType.valueOf(it.uppercase()) })
