package bosca.kubernetes.controller

/**
 * Marker singleton backing the `KubernetesMutations` GraphQL type — every
 * kubernetes mutation is nested under `mutation { kubernetes { ... } }`.
 * The server's central `MutationController.kubernetes()` returns this
 * object; field resolvers hang off it via [KubernetesMutationsController].
 */
object KubernetesMutations
