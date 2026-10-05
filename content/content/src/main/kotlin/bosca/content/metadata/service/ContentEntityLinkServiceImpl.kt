package bosca.content.metadata.service

import bosca.content.metadata.model.ContentEntityLink
import bosca.content.metadata.model.ContentEntityLinkExtractor
import bosca.content.metadata.model.ContentLinkTarget
import bosca.content.metadata.repository.ContentEntityLinkRepository
import bosca.documents.Content
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class ContentEntityLinkServiceImpl(
    private val repository: ContentEntityLinkRepository,
) : ContentEntityLinkService {

    override suspend fun extractAndStore(metadataId: UUID, metadataVersion: Int, content: Content) {
        repository.deleteBySource(metadataId, metadataVersion)
        val extracted = ContentEntityLinkExtractor.extract(content)
        for (link in extracted) {
            repository.add(
                metadataId = metadataId,
                metadataVersion = metadataVersion,
                targetType = link.targetType,
                targetId = link.targetId,
                nodeType = link.nodeType,
                position = link.position,
            )
        }
    }

    override suspend fun listBySource(metadataId: UUID, metadataVersion: Int): List<ContentEntityLink> =
        repository.listBySource(metadataId, metadataVersion)

    override suspend fun listByTarget(targetType: ContentLinkTarget, targetId: String): List<ContentEntityLink> =
        repository.listByTarget(targetType, targetId)

    override suspend fun listByMetadata(metadataId: UUID): List<ContentEntityLink> =
        repository.listByMetadata(metadataId)

    override suspend fun deleteByMetadata(metadataId: UUID) =
        repository.deleteByMetadata(metadataId)
}
