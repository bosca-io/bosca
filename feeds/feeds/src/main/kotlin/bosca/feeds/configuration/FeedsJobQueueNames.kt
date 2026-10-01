package bosca.feeds.configuration

/**
 * Names for the feeds job queue. [feedsJobQueue] is the DI provider name a `@JobDefinition`
 * references in `queue = …`; [feedsRunner] is the `JobRunner` provider name the runner enables via
 * `runners.enabled`; [feedsQueue] is the physical queue name passed to `factory.create(…)`.
 */
object FeedsJobQueueNames {
    const val feedsJobQueue = "feedsJobQueue"
    const val feedsRunner = "feedsQueueRunner"
    const val feedsQueue = "feeds"
}
