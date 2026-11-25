package bosca.ai.kit.harness.runtime

/** Safe configuration failure that may be shown without exposing registry data. */
class KitMcpConfigurationException(message: String) : IllegalArgumentException(message)
