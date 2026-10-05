package bosca.slug.service

import bosca.content.collection.model.CollectionCacheKeyId
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.Service
import bosca.slug.model.Slug

/**
 * Manages URL-friendly slug mappings for metadata, collections, and profiles.
 *
 * Slugs provide human-readable, SEO-friendly identifiers that map to internal UUIDs.
 * This service handles creation, lookup, batch loading for GraphQL data loaders,
 * and deletion of slug associations across all entity types.
 */
interface SlugService : Service {

    /**
     * Persists a new slug mapping and returns the stored [Slug].
     *
     * @param slug the slug mapping to create
     * @return the persisted slug
     */
    suspend fun add(slug: Slug): Slug

    /**
     * Looks up a [Slug] by its URL-friendly string value.
     *
     * @param slug the slug string to look up
     * @return the matching [Slug], or `null` if not found
     */
    suspend fun get(slug: String): Slug?

    /**
     * Retrieves the slug string associated with a metadata item.
     *
     * @param id the metadata item's UUID
     * @return the slug string, or `null` if no slug is mapped
     */
    suspend fun getMetadataSlug(id: UUID): String?

    /**
     * Populates the given [batch] with slug values for multiple metadata items at once,
     * used by GraphQL batch/data-loader resolution to avoid N+1 queries.
     *
     * @param batch the batch to populate with metadata ID to slug mappings
     */
    suspend fun addMetadataSlugsToBatch(batch: Batch<MetadataCacheKeyId, String>)

    /**
     * Deletes the slug association for the given metadata item.
     *
     * @param id the metadata item's UUID whose slug should be removed
     */
    suspend fun deleteMetadataSlug(id: UUID)

    /**
     * Retrieves the slug string associated with a collection, optionally filtered by language.
     *
     * @param id the collection's UUID
     * @param languageTag optional BCP-47 language tag to select a language-specific slug
     * @return the slug string, or `null` if no slug is mapped
     */
    suspend fun getCollectionSlug(id: UUID, languageTag: String? = null): String?

    /**
     * Populates the given [batch] with slug values for multiple collections at once,
     * used by GraphQL batch/data-loader resolution to avoid N+1 queries.
     *
     * @param batch the batch to populate with collection ID to slug mappings
     */
    suspend fun addCollectionSlugsToBatch(batch: Batch<CollectionCacheKeyId, String>)

    /**
     * Deletes the slug association for the given collection.
     *
     * @param id the collection's UUID whose slug should be removed
     * @param languageTag optional BCP-47 language tag to delete a language-specific slug
     */
    suspend fun deleteCollectionSlug(id: UUID, languageTag: String? = null)

    /**
     * Retrieves the slug string associated with a profile.
     *
     * @param id the profile's UUID
     * @return the slug string, or `null` if no slug is mapped
     */
    suspend fun getProfileSlug(id: UUID): String?

    /**
     * Populates the given [batch] with slug values for multiple profiles at once,
     * used by GraphQL batch/data-loader resolution to avoid N+1 queries.
     *
     * @param batch the batch to populate with profile UUID to slug mappings
     */
    suspend fun addProfileSlugsToBatch(batch: Batch<UUID, String>)

    /**
     * Deletes the slug association for the given profile.
     *
     * @param id the profile's UUID whose slug should be removed
     */
    suspend fun deleteProfileSlug(id: UUID)

    /**
     * Deletes a slug mapping by its string value.
     *
     * @param slug the slug string to delete
     */
    suspend fun delete(slug: String)

    /**
     * Generates a URL-friendly slug from the given [text] by normalizing, transliterating,
     * and de-duplicating as needed.
     *
     * @param text the source text to convert into a slug
     * @return a unique, URL-safe slug string
     */
    suspend fun createSlug(text: String): String
}