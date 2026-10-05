package bosca.experimentation.graphql

import bosca.experimentation.model.AiInsights
import bosca.experimentation.model.AnalysisReport
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Resolves fields on the AnalysisReport GraphQL type. Explicit `@Field`
 * resolvers are required because the framework does not autobind type fields
 * from data class properties.
 */
@TypeController(type = "AnalysisReport")
class AnalysisReportTypeController(
    private val json: Json,
) : GraphQLController<AnalysisReport> {

    @Field
    fun id(report: AnalysisReport): UUID = report.id

    @Field
    fun experimentId(report: AnalysisReport): UUID = report.experimentId

    @Field
    fun summary(report: AnalysisReport): String = report.summary

    @Field
    fun recommendation(report: AnalysisReport): String = report.recommendation

    @Field
    fun details(report: AnalysisReport): JsonElement = report.details

    @Field
    fun confidence(report: AnalysisReport): Double? = report.confidence

    /**
     * Decodes the persisted `ai_insights` jsonb blob into the typed
     * [AiInsights] surface. Throws on a schema mismatch rather than
     * silently resolving null — the AI analyzer writes this column
     * exclusively through the typed serializer, so any drift is a
     * bug that should surface, not hide.
     */
    @Field
    fun aiInsights(report: AnalysisReport): AiInsights? {
        val element = report.aiInsights ?: return null
        return json.decodeFromJsonElement(AiInsights.serializer(), element)
    }

    @Field
    fun created(report: AnalysisReport): OffsetDateTime = report.created
}
