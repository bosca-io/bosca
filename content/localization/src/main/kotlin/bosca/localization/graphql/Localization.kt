package bosca.localization.graphql

/**
 * Marker object resolved by the top-level `Query.localization` field; the fields declared
 * on the `Localization` GraphQL type are resolved by [LocalizationQueryController].
 */
object Localization

/**
 * Marker object for `Mutation.localization`. Mutation fields are resolved by
 * [LocalizationMutationController].
 */
object LocalizationMutation
