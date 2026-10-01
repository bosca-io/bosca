package bosca.scheduler.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.scheduler.model.CronValidationResult

@TypeController
class CronValidationResultController : GraphQLController<CronValidationResult> {

    @Field
    fun valid(validation: CronValidationResult) = validation.valid

    @Field
    fun error(validation: CronValidationResult) = validation.error

    @Field
    fun nextRuns(validation: CronValidationResult) = validation.nextRuns
}
