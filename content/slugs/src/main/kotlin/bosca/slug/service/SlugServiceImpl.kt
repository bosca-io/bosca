@file:OptIn(ExperimentalTime::class)

package bosca.slug.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.StringKeySerializer
import bosca.cache.serializers.UUIDKeySerializer
import bosca.content.collection.model.CollectionCacheKeyId
import bosca.content.collection.model.CollectionCacheKeySerializer
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataCacheKeySerializer
import bosca.db.transaction
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.slug.model.Slug
import bosca.slug.model.slugify
import bosca.slug.repository.SlugRepository
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@ServiceImplementation
class SlugServiceImpl(private val repository: SlugRepository) : SlugService {

    private val slugById = ServiceCache("slugs", StringKeySerializer) {
        val slug = repository.get(it)
        slug
    }

    private val slugByMetadataId = ServiceCache(
        "slugs:metadata",
        MetadataCacheKeySerializer,
        { keys, batch ->
            val ids = repository.getSlugByMetadataIds(keys.map { it.id }).associateBy { it.metadataId }
            keys.forEach {
                ids[it.id]?.slug?.let { slug ->
                    batch.setData(it, slug)
                }
            }
        }
    ) {
        repository.getSlugByMetadataId(it.id)?.slug
    }

    private val slugByCollectionId = ServiceCache(
        cacheName = "slugs:collection::id",
        CollectionCacheKeySerializer,
        { keys, batch ->
            val ids = repository.getSlugByCollectionIds(keys.map { it.id }).groupBy { it.collectionId }
            keys.forEach { key ->
                val slugs = ids[key.id]
                val slug = slugs?.find { it.languageTag == key.languageTag } ?: slugs?.find { it.languageTag == null }
                slug?.let {
                    batch.setData(key, it.slug)
                }
            }
        }
    ) {
        val languageTag = it.languageTag
        if (languageTag == null) {
            repository.getSlugByCollectionId(it.id)?.slug
        } else {
            repository.getSlugByCollectionId(it.id, languageTag)?.slug
        }
    }

    private val slugByProfileId = ServiceCache(cacheName = "slugs:profile", UUIDKeySerializer, { keys, batch ->
        repository.getSlugByProfileId(keys).forEach { slug ->
            batch.setData(slug.profileId ?: error("missing profile id"), slug.slug)
        }
    }) {
        repository.getSlugByProfileId(it)?.slug
    }

    private suspend fun removeFromCache(slug: Slug) {
        slugById.remove(slug.slug)
        slug.metadataId?.let { slugByMetadataId.remove(MetadataCacheKeyId(it), keyPrefix = true) }
        slug.collectionId?.let { slugByCollectionId.remove(CollectionCacheKeyId(it, languageTag = slug.languageTag)) }
        slug.profileId?.let { slugByProfileId.remove(it) }
    }

    override suspend fun add(slug: Slug): Slug {
        val added = if (repository.get(slug.slug) != null) {
            var tries = 200
            var newSlug: Slug? = null
            var index = Clock.System.now().epochSeconds
            while (newSlug == null && tries-- > 0) {
                try {
                    newSlug = transaction {
                        repository.add(slug.copy(slug = slug.slug + index))
                    }
                } catch (_: Exception) {
                    index++
                    continue
                }
            }
            if (newSlug == null) {
                val id = slug.metadataId ?: slug.collectionId ?: slug.profileId ?: error("missing id")
                newSlug = transaction {
                    repository.add(slug.copy(slug = slug.slug + id))
                }
            }
            newSlug
        } else {
            repository.add(slug)
        }
        removeFromCache(added)
        return added
    }

    override suspend fun get(slug: String) = slugById.get(slug)

    override suspend fun getMetadataSlug(id: UUID): String? = slugByMetadataId.get(MetadataCacheKeyId(id))

    override suspend fun addMetadataSlugsToBatch(batch: Batch<MetadataCacheKeyId, String>) {
        slugByMetadataId.addToBatch(batch)
    }

    override suspend fun deleteMetadataSlug(id: UUID) {
        val slug = repository.getSlugByMetadataId(id)
        if (slug != null) {
            repository.deleteSlugByMetadataId(id)
            removeFromCache(slug)
        }
        slugByMetadataId.remove(MetadataCacheKeyId(id), keyPrefix = true)
    }

    override suspend fun getCollectionSlug(id: UUID, languageTag: String?) = slugByCollectionId.get(CollectionCacheKeyId(id, languageTag = languageTag))

    override suspend fun addCollectionSlugsToBatch(batch: Batch<CollectionCacheKeyId, String>) {
        slugByCollectionId.addToBatch(batch)
    }

    override suspend fun deleteCollectionSlug(id: UUID, languageTag: String?) {
        if (languageTag == null) {
            val slug = repository.getSlugByCollectionId(id)
            if (slug != null) {
                repository.deleteSlugByCollectionId(id)
                removeFromCache(slug)
            }
        } else {
            val slug = repository.getSlugByCollectionId(id, languageTag)
            if (slug != null) {
                repository.deleteSlugByCollectionId(id, languageTag)
                removeFromCache(slug)
            }
        }
        slugByCollectionId.remove(CollectionCacheKeyId(id, languageTag = languageTag))
    }

    override suspend fun getProfileSlug(id: UUID) = slugByProfileId.get(id)

    override suspend fun addProfileSlugsToBatch(batch: Batch<UUID, String>) {
        slugByProfileId.addToBatch(batch)
    }

    override suspend fun deleteProfileSlug(id: UUID) {
        val slugs = repository.getSlugByProfileId(listOf(id))
        repository.deleteSlugByProfileId(id)
        slugs.forEach { removeFromCache(it) }
        slugByProfileId.remove(id)
    }

    override suspend fun delete(slug: String) {
        val s = repository.get(slug)
        repository.deleteById(slug)
        s?.let { removeFromCache(it) }
    }

    override suspend fun createSlug(text: String): String = text.slugify()
}