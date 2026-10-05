package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.Cluster
import bosca.kubernetes.model.ClusterEnvironment
import bosca.kubernetes.model.ClusterHealth
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Field-level projection for the GraphQL `Cluster` type. Pure record
 * accessors — no IO. Authorization is enforced one level up at
 * [KubernetesQueriesController], so a client can never reach this
 * controller without first proving they are in the `administrators`
 * group. Mutations live on [KubernetesMutationsController].
 *
 * The encrypted kubeconfig backing the cluster is never reachable from
 * here; it lives on a separate table accessed only by
 * `ClusterCredentialService`.
 */
@TypeController(type = "Cluster")
class ClusterTypeController : GraphQLController<Cluster> {
    @Field fun id(c: Cluster): UUID = c.id
    @Field fun name(c: Cluster): String = c.name
    @Field fun provider(c: Cluster): String = c.provider
    @Field fun region(c: Cluster): String = c.region
    @Field fun environment(c: Cluster): ClusterEnvironment = c.environment
    @Field fun version(c: Cluster): String = c.serverVersion
    @Field fun health(c: Cluster): ClusterHealth = c.health
    @Field fun nodes(c: Cluster): Int = c.nodes
    @Field fun pods(c: Cluster): Int = c.pods
    @Field fun registeredAt(c: Cluster): OffsetDateTime = c.registeredAt
    @Field fun lastSeenAt(c: Cluster): OffsetDateTime? = c.lastSeenAt
    @Field fun optimisticVersion(c: Cluster): Long = c.version
}
