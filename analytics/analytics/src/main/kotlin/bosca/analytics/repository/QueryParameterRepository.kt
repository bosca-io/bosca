package bosca.analytics.repository

import bosca.analytics.model.AnalyticsQueryParameter
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface QueryParameterRepository {

    @Query("select * from analytics_query_parameters where query_id = :queryId order by sort asc")
    suspend fun getParameters(queryId: UUID): List<AnalyticsQueryParameter>

    @Query("select * from analytics_query_parameters where query_id in (:queryIds) order by query_id, sort asc")
    suspend fun getParametersForQueries(queryIds: List<UUID>): List<AnalyticsQueryParameter>

    @Query("insert into analytics_query_parameters (query_id, parameter, name, description, type, array_type, default_value, required, sort) values (:queryId, :parameter, :name, :description, (:type)::analytics_query_parameter_type, (:arrayType)::analytics_query_parameter_type, :defaultValue, :required, :sort)")
    suspend fun addParameter(query: AnalyticsQueryParameter)

    @Query("update analytics_query_parameters set name = :name, description = :description, type = (:type)::analytics_query_parameter_type, array_type = (:arrayType)::analytics_query_parameter_type, default_value = :defaultValue, required = :required, sort = :sort where query_id = :queryId and parameter = :parameter")
    suspend fun editParameter(query: AnalyticsQueryParameter)

    @Query("delete from analytics_query_parameters where query_id = :queryId and parameter = :parameter")
    suspend fun deleteParameter(queryId: UUID, parameter: String)

    @Query("delete from analytics_query_parameters where query_id = :queryId")
    suspend fun deleteParametersById(queryId: UUID)
}