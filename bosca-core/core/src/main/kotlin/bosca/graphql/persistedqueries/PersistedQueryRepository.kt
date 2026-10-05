package bosca.graphql.persistedqueries

import bosca.db.annotation.Query
import bosca.db.annotation.Repository

/**
 * Database repository for storing and retrieving GraphQL persisted queries.
 *
 * Persisted queries allow clients to register GraphQL operations by their SHA-256 hash,
 * then execute them by hash alone. This reduces payload size and enables query whitelisting.
 * Queries are scoped per application to support multi-tenant usage.
 */
@Repository
interface PersistedQueryRepository {

    /**
     * Finds all persisted queries matching the given SHA-256 hash, across all applications.
     *
     * @param sha256 the SHA-256 hash of the query
     * @return a list of matching [PersistedQuery] entries (may span multiple applications)
     */
    @Query("SELECT * FROM gql_persisted_queries WHERE sha256 = :sha256")
    suspend fun findBySha256(sha256: String): List<PersistedQuery>

    /**
     * Finds a single persisted query for a specific application and SHA-256 hash.
     *
     * @param application the application identifier
     * @param sha256 the SHA-256 hash of the query
     * @return the matching [PersistedQuery], or `null` if not found
     */
    @Query("SELECT * FROM gql_persisted_queries WHERE application = :application AND sha256 = :sha256")
    suspend fun findByApplicationAndSha256(application: String, sha256: String): PersistedQuery?

    /**
     * Inserts or updates a persisted query for the given application and SHA-256 hash.
     * If a query with the same application and hash already exists, its query text is updated.
     *
     * @param application the application identifier
     * @param query the GraphQL query text
     * @param sha256 the SHA-256 hash of the query
     * @return the upserted [PersistedQuery]
     */
    @Query("INSERT INTO gql_persisted_queries (application, query, sha256) VALUES (:application, :query, :sha256) ON CONFLICT (application, sha256) DO UPDATE SET query = :query RETURNING *")
    suspend fun upsert(application: String, query: String, sha256: String): PersistedQuery

    /**
     * Deletes a persisted query by application and SHA-256 hash.
     *
     * @param application the application identifier
     * @param sha256 the SHA-256 hash of the query to delete
     * @return the number of rows deleted
     */
    @Query("DELETE FROM gql_persisted_queries WHERE application = :application AND sha256 = :sha256")
    suspend fun delete(application: String, sha256: String): Int
}
