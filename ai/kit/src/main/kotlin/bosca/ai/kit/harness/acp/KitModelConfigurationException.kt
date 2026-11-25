package bosca.ai.kit.harness.acp

/** A safe settings failure before any prompt is submitted. */
class KitModelConfigurationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
