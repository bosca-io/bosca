package bosca.analytics.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AnalyticsQueryParameter(
    @ColumnName("query_id")
    @Contextual
    val queryId: UUID,
    val parameter: String,
    val name: String,
    val description: String,
    val type: QueryParameterType,
    @ColumnName("array_type")
    val arrayType: QueryParameterType = QueryParameterType.NONE,
    @ColumnName("default_value")
    val defaultValue: JsonElement?,
    val required: Boolean,
    val sort: Int
)

@DbMapper(QueryParameterTypeMapper::class)
@Serializable
enum class QueryParameterType {
    STRING,
    INTEGER,
    FLOAT,
    BOOLEAN,
    DATE,
    TIME,
    DATETIME,
    ARRAY,
    OBJECT,
    NONE
}

object QueryParameterTypeMapper : EnumMapper<QueryParameterType>({ QueryParameterType.valueOf(it.uppercase()) })