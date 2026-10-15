package bosca.analytics.repository.nats

import java.io.IOException

/** A publishing failure tracked by NATS health, which can recover independently of processing. */
class NatsPublishException(cause: Exception) : IOException("Failed to publish analytics events to NATS", cause)
