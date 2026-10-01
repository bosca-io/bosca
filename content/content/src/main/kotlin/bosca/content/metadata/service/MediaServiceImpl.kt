package bosca.content.metadata.service

import bosca.cache.ServiceCache
import bosca.content.metadata.model.Media
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataCacheKeySerializer
import bosca.content.metadata.repository.MediaRepository
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Default implementation of [MediaService] that persists media records
 * in the `metadata_media` table and caches lookups using a [ServiceCache]
 * keyed by metadata identifier.
 */
@ServiceImplementation
class MediaServiceImpl(
    private val mediaRepository: MediaRepository,
) : MediaService {

    private val mediaCache = ServiceCache(
        cacheName = "media",
        serializer = MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val results = mediaRepository.getByMetadataIds(keys.map { it.id })
                .associateBy { it.metadataId }
            for (key in keys) {
                batch.setData(key, results[key.id] ?: continue)
            }
        }
    ) {
        mediaRepository.getByMetadataId(it.id)
    }

    override suspend fun getMedia(metadataId: UUID): Media? =
        mediaCache.get(MetadataCacheKeyId(metadataId))

    override suspend fun addMedia(media: Media): Media {
        val result = mediaRepository.add(media)
        mediaCache.remove(MetadataCacheKeyId(media.metadataId), keyPrefix = true)
        return result
    }

    override suspend fun updateMedia(media: Media): Media {
        val result = mediaRepository.update(media)
        mediaCache.remove(MetadataCacheKeyId(media.metadataId), keyPrefix = true)
        return result
    }

    override suspend fun deleteMedia(metadataId: UUID) {
        mediaRepository.deleteByMetadataId(metadataId)
        mediaCache.remove(MetadataCacheKeyId(metadataId), keyPrefix = true)
    }

    override suspend fun addToBatch(batch: Batch<MetadataCacheKeyId, Media>) {
        mediaCache.addToBatch(batch)
    }
}
