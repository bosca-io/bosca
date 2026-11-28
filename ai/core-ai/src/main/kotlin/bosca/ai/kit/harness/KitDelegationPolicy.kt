package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Opt-in child harness choices and aggregate reservations for one initiating parent turn. */
@Serializable
data class KitDelegationPolicy(
    val profiles: List<String>,
    val maxDepth: Int = 1,
    val maxActive: Int = 1,
    val maxChildren: Int = 4,
    val maxRuntimeSeconds: Long = 600,
    val totalRuntimeSeconds: Long = 1800,
) {
    /** Rejects unbounded or internally inconsistent delegation policies at use time. */
    fun validate() {
        require(profiles.isNotEmpty() && profiles.distinct().size == profiles.size && profiles.all(String::isNotBlank) &&
            maxDepth in 1..4 && maxActive in 1..16 && maxChildren in maxActive..64 &&
            maxRuntimeSeconds in 1..86400 && totalRuntimeSeconds in maxRuntimeSeconds..604800) { "Invalid child harness limits" }
    }
}
