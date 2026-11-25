package bosca.pipelines.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pipelines.model.PipelineGraphValidation
import kotlinx.serialization.json.JsonElement

@TypeController
class PipelineGraphValidationController : GraphQLController<PipelineGraphValidation> {
    @Field fun valid(source: PipelineGraphValidation): Boolean = source.valid
    @Field fun errors(source: PipelineGraphValidation): List<String> = source.errors
    @Field fun graph(source: PipelineGraphValidation): JsonElement = source.graph
}
