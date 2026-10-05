package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.Namespace

/**
 * Field-level projection for the GraphQL `Namespace` type. Pure record
 * accessors — authorization is enforced at the parent query resolver.
 */
@TypeController(type = "Namespace")
class NamespaceTypeController : GraphQLController<Namespace> {
    @Field fun name(n: Namespace): String = n.name
    @Field fun status(n: Namespace): String = n.status
    @Field fun workloads(n: Namespace): Int = n.workloads
    @Field fun pods(n: Namespace): Int = n.pods
    @Field fun services(n: Namespace): Int = n.services
    @Field fun age(n: Namespace): String = n.age
}
