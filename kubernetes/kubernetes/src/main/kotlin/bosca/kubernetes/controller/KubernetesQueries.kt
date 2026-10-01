package bosca.kubernetes.controller

/**
 * Marker singleton backing the `KubernetesQueries` GraphQL type — every
 * kubernetes read is nested under `query { kubernetes { ... } }`. The
 * server's central `QueryController.kubernetes()` returns this object;
 * field resolvers hang off it via [KubernetesQueriesController].
 */
object KubernetesQueries
