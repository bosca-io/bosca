package bosca.ecommerce.configuration

/**
 * Names for the ecommerce job queue. [ecomJobQueue] is the DI provider name a `@JobDefinition`
 * references in `queue = …`; [ecomRunner] is the `JobRunner` provider name listed in the runner's
 * `runners.enabled` config; [ecomQueue] is the physical queue name passed to `factory.create(…)`.
 */
object JobQueueNames {
    const val ecomJobQueue = "ecomJobQueue"
    const val ecomRunner = "ecomQueueRunner"
    const val ecomQueue = "ecom"
}
