package bosca.experimentation.configuration

/**
 * Constants for experimentation job queue naming used in DI provider registration
 * and job definition annotations.
 */
object JobQueueNames {
    const val experimentationJobQueue = "experimentationQueue"
    const val experimentationRunner = "experimentationQueueRunner"
    const val experimentationQueue = "experimentation"
}
