package bosca.scripting.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.scripting.model.ScriptValidationResult

@TypeController
class ScriptValidationResultController : GraphQLController<ScriptValidationResult> {
    @Field fun valid(source: ScriptValidationResult) = source.valid
    @Field fun compiled(source: ScriptValidationResult) = source.compiled
    @Field fun errors(source: ScriptValidationResult) = source.errors
}
