package bosca.postgres.admin.graphql

/**
 * Marker object representing the `PostgresAdmin` GraphQL query type.
 * Returned by the root [QueryController] to namespace all PostgreSQL monitoring queries.
 */
object PostgresAdmin

/**
 * Marker object representing the `PostgresAdminMutation` GraphQL mutation type.
 * Returned by the root [MutationController] to namespace all PostgreSQL admin mutations.
 */
object PostgresAdminMutation
