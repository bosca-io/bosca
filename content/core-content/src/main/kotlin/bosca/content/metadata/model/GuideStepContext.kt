package bosca.content.metadata.model

import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.serialization.ZonedDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@BatchKey(type = MetadataCacheKeyId::class)
@Serializable
class GuideStepContext(
    val guide: Guide,
    val guideStep: GuideStep,
    @Contextual
    val date: OffsetDateTime?
) : MetadataCacheKeyable {

    override val metadataId: UUID = guide.metadataId
    override val version: Int = guide.version
    override val step: Long = guideStep.id

    @Transient
    override val key: String? = null

}