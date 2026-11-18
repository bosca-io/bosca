package bosca.cli.swarm

/** Snapshot taken before uploads or pulls; a failed rollout never advances image history. */
internal data class SwarmStorageState(
    val services: List<SwarmStoredService>,
    val history: SwarmImageHistory,
)
