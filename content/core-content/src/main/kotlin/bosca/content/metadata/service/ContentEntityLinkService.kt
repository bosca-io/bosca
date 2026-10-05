package bosca.content.metadata.service

import bosca.content.metadata.model.ContentEntityLink
import bosca.content.metadata.model.ContentLinkTarget
import bosca.documents.Content
import bosca.serialization.UUID
import bosca.service.Service

interface ContentEntityLinkService : Service {

    suspend fun extractAndStore(metadataId: UUID, metadataVersion: Int, content: Content)

    suspend fun listBySource(metadataId: UUID, metadataVersion: Int): List<ContentEntityLink>

    suspend fun listByTarget(targetType: ContentLinkTarget, targetId: String): List<ContentEntityLink>

    suspend fun listByMetadata(metadataId: UUID): List<ContentEntityLink>

    suspend fun deleteByMetadata(metadataId: UUID)
}
