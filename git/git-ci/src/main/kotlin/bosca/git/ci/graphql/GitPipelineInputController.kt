package bosca.git.ci.graphql

import bosca.git.model.PipelineInputDefinition
import bosca.git.model.PipelineInputType
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Exposes a named trigger declaration through the pipeline input schema. */
@TypeController(type = "GitPipelineInputDefinition")
class GitPipelineInputController : GraphQLController<PipelineInputDefinition> {
    @Field fun name(input: PipelineInputDefinition) = input.name
    @Field fun type(input: PipelineInputDefinition) = PipelineInputType.valueOf(input.declaration.type.uppercase())
    @Field fun defaultValue(input: PipelineInputDefinition) = input.declaration.default
    @Field fun description(input: PipelineInputDefinition) = input.declaration.description
    @Field fun options(input: PipelineInputDefinition) = input.declaration.options
    @Field fun required(input: PipelineInputDefinition) = input.declaration.default == null
}
