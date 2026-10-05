package bosca.content.metadata.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(ContainerTypeMapper::class)
@Serializable
enum class ContainerType {
    STANDARD,
    BIBLE,
    METADATA
}

object ContainerTypeMapper : EnumMapper<ContainerType>({ ContainerType.valueOf(it.uppercase()) })