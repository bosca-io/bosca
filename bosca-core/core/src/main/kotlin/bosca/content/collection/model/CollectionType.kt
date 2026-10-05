package bosca.content.collection.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(CollectionTypeMapper::class)
@Serializable
enum class CollectionType {
    FOLDER,
    QUEUE,
    ROOT,
    STANDARD,
    SYSTEM
}

object CollectionTypeMapper : EnumMapper<CollectionType>({ CollectionType.valueOf(it.uppercase()) })