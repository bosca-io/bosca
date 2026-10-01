package bosca.recommendations.configuration

/**
 * Defines the NATS/Redis queue and runner identifiers used by the recommendation
 * engine's asynchronous job processing pipeline, ensuring consistent naming
 * across job producers and consumers.
 */
object JobQueueNames {
    const val recommendationsJobQueue = "recommendationsQueue"
    const val recommendationsRunner = "recommendationsQueueRunner"
    const val recommendationsQueue = "recommendations"
}
