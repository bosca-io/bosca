package bosca.analytics.repository

import bosca.analytics.model.AnalyticsQuery
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface QueryDefinitionRepository {

    @Query("select * from analytics_queries order by name offset :offset limit :limit")
    suspend fun getAll(offset: Long, limit: Int): List<AnalyticsQuery>

    @Query("select * from analytics_queries where id = :id")
    suspend fun getById(id: UUID): AnalyticsQuery?

    @Query("select * from analytics_queries where id = :id for update")
    suspend fun lockById(id: UUID): AnalyticsQuery?

    @Query("select * from analytics_queries where key = :key")
    suspend fun getByKey(key: String): AnalyticsQuery?

    @Query("insert into analytics_queries (key, name, description, query, configuration, refresh_interval_seconds) values (:key, :name, :description, :query, :configuration, :refreshIntervalSeconds) returning *")
    suspend fun add(query: AnalyticsQuery): AnalyticsQuery

    @Query(
        """
        update analytics_queries
        set key = :key,
            name = :name,
            description = :description,
            query = :query,
            configuration = :configuration,
            refresh_interval_seconds = :refreshIntervalSeconds,
            cache_generation = cache_generation + 1
        where id = :id
        returning *
        """
    )
    suspend fun edit(query: AnalyticsQuery): AnalyticsQuery

    @Query("delete from analytics_queries where id = :id")
    suspend fun deleteById(id: UUID)
}
