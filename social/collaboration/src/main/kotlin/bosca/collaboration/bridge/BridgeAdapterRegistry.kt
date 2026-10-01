package bosca.collaboration.bridge

/**
 * Registry of platform-specific bridge adapters, allowing the outbound
 * job executor to select the correct adapter based on a binding's platform.
 */
class BridgeAdapterRegistry(adapters: List<BridgePlatformAdapter>) {

    private val adapterMap = adapters.associateBy { it.platform }

    fun get(platform: BridgePlatform): BridgePlatformAdapter =
        adapterMap[platform] ?: throw IllegalArgumentException("No adapter registered for platform: $platform")

    fun hasAdapter(platform: BridgePlatform): Boolean = platform in adapterMap
}
