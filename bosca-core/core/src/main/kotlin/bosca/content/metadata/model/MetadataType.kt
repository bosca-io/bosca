package bosca.content.metadata.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(MetadataTypeMapper::class)
@Serializable
enum class MetadataType {
    STANDARD,
    VARIANT
}

object MetadataTypeMapper : EnumMapper<MetadataType>({ MetadataType.valueOf(it.uppercase()) })