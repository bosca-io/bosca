package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.Workload
import bosca.kubernetes.model.WorkloadKind
import bosca.kubernetes.model.WorkloadStatus
import kotlinx.serialization.json.JsonElement

/**
 * Field-level projection for the GraphQL `Workload` type. The `labels`
 * scalar is forwarded as the raw JSON map straight from upstream
 * `metadata.labels`. Authorization is enforced one level up at
 * [KubernetesQueriesController].
 */
@TypeController(type = "Workload")
class WorkloadTypeController : GraphQLController<Workload> {
    @Field fun id(w: Workload): String = w.id
    @Field fun kind(w: Workload): WorkloadKind = w.kind
    @Field fun name(w: Workload): String = w.name
    @Field fun namespace(w: Workload): String = w.namespace
    @Field fun ready(w: Workload): Int = w.ready
    @Field fun desired(w: Workload): Int = w.desired
    @Field fun status(w: Workload): WorkloadStatus = w.status
    @Field fun image(w: Workload): String = w.image
    @Field fun age(w: Workload): String = w.age
    @Field fun cpu(w: Workload): Double = w.cpu
    @Field fun memory(w: Workload): Double = w.memory
    @Field fun restarts(w: Workload): Int = w.restarts
    @Field fun strategy(w: Workload): String = w.strategy
    @Field fun labels(w: Workload): JsonElement? = w.labels
}
