package bosca.search

/**
 * Indicates that an entity can optionally be indexed in the search engine (e.g., Meilisearch).
 *
 * Implementations control whether the entity should be included in the search index
 * via [isSearchable]. Entities that are not searchable are excluded from indexing
 * operations even if they otherwise qualify.
 */
interface Indexable {

    /** Whether this entity should be included in the search index. */
    val isSearchable: Boolean
}