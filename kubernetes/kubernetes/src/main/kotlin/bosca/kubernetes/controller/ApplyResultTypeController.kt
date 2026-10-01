package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.ApplyFailure
import bosca.kubernetes.model.ApplyResult

/**
 * Field projection for the GraphQL `ApplyResult` type.
 *
 * The shape is a literal one-to-one with the data class — the type
 * controller exists so KSP wires per-field dispatchers; no behaviour
 * here beyond projection.
 */
@TypeController(type = "ApplyResult")
class ApplyResultTypeController : GraphQLController<ApplyResult> {
    @Field fun succeeded(r: ApplyResult): Boolean = r.succeeded
    @Field fun applied(r: ApplyResult): List<String> = r.applied
    @Field fun failed(r: ApplyResult): List<ApplyFailure> = r.failed
    @Field fun dryRun(r: ApplyResult): String? = r.dryRun
}

/** Field projection for the GraphQL `ApplyFailure` type. */
@TypeController(type = "ApplyFailure")
class ApplyFailureTypeController : GraphQLController<ApplyFailure> {
    @Field fun resource(f: ApplyFailure): String = f.resource
    @Field fun error(f: ApplyFailure): String = f.error
}
