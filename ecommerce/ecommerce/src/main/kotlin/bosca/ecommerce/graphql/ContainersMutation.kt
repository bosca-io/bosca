package bosca.ecommerce.graphql

import bosca.serialization.UUID

/** GraphQL namespace marker for container (box catalog) admin operations. */
object ContainersMutation

/** Id-scoped mutation namespace for one container. */
data class ContainerMutation(val id: UUID)
