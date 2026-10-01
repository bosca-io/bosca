package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
class DataInput(
    @Contextual
    val templateMetadataId: UUID? = null,
    val templateMetadataVersion: Int? = null,
    val type: DataType? = null,
) {

    fun toData(metadata: Metadata) = toData(metadata.id, metadata.version)

    fun toData(id: UUID, version: Int) = Data(
        metadataId = id,
        version = version,
        templateMetadataId = templateMetadataId,
        templateMetadataVersion = templateMetadataVersion,
        type = type ?: DataType.ATTRIBUTES
    )
}