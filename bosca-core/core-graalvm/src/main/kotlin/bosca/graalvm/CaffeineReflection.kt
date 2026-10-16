package bosca.graalvm

/**
 * Factory implementations for the strong-key, strong-value caches used by Bosca.
 * Caffeine's LocalCacheFactory and NodeFactory encode the same features in different orders.
 * These names are resolved only during native-image analysis.
 */
internal fun caffeineFactoryClassNames(): List<String> = buildList {
    val packageName = "com.github.benmanes.caffeine.cache"
    for (maximum in listOf("", "MS", "MW")) {
        for (expiration in listOf("", "A", "W", "AW")) {
            for (refresh in listOf("", "R")) {
                // Nodes: strong keys/values, expiration, refresh, maximum size/weight.
                add("$packageName.PS$expiration$refresh$maximum")
                for (listener in listOf("", "L")) {
                    for (stats in listOf("", "S")) {
                        // Caches: strong keys/values, removal listener, stats, maximum, expiration, refresh.
                        add("$packageName.SS$listener$stats$maximum$expiration$refresh")
                    }
                }
            }
        }
    }
}
