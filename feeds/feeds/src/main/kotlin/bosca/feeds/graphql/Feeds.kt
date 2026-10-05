package bosca.feeds.graphql

import bosca.serialization.UUID

/**
 * GraphQL namespace markers for the feeds domain. Reads hang off [Feeds] (`Query.feeds`) and writes
 * off [FeedsMutation] (`Mutation.feeds`) — nothing leaks onto the global roots. The root fields are
 * declared in `feeds.graphqls` and resolved by the server's Query/Mutation controllers
 * (`fun feeds() = Feeds` / `= FeedsMutation`); field resolution within the namespaces is handled by
 * the controllers in this package.
 */
object Feeds

object FeedsMutation

/** Feed source creates + the per-source instance accessor namespace. */
object FeedSourcesMutation

/** Id-scoped mutation namespace for one feed source. */
data class FeedSourceMutation(val id: UUID)

/** Self-service feed source registration + the per-source instance accessor for the authenticated caller. */
object FeedUserSourcesMutation

/** Id-scoped mutation namespace for one of the caller's own feed sources. */
data class FeedUserSourceMutation(val id: UUID)

/** The authenticated caller's feed-source subscriptions namespace. */
object FeedSubscriptionsMutation
