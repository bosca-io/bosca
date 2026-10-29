package bosca.atlas.server

import bosca.server.config.ApplicationConfig

/** Selects Atlas's HTTP surfaces and background execution responsibilities for this process. */
data class AtlasConfiguration(
    val apiEnabled: Boolean = true,
    val gitEnabled: Boolean = true,
    val artifactsEnabled: Boolean = true,
    val uiEnabled: Boolean = true,
    val runnerEnabled: Boolean = true,
    val runnerConcurrency: Int = 16,
    val runnerQueues: List<String> = listOf("git", "pipelines", "common"),
    val runnerListeners: Boolean = true,
) {
    init {
        require(runnerConcurrency > 0) { "Atlas runner concurrency must be positive" }
        require(runnerQueues.all { it in setOf("git", "pipelines", "common") }) { "Unknown Atlas runner queue" }
        require(runnerQueues.distinct().size == runnerQueues.size) { "Atlas runner queues must be distinct" }
        require(!uiEnabled || apiEnabled) { "Embedded Atlas UI requires the API; use atlas-ui-server for a remote API" }
        require(apiEnabled || gitEnabled || artifactsEnabled || runnerEnabled) { "At least one Atlas backend responsibility must be enabled" }
    }

    companion object {
        /** Reads per-process role configuration; queue names select the existing durable queues. */
        fun from(config: ApplicationConfig): AtlasConfiguration {
            fun enabled(key: String, default: Boolean = true) =
                config.propertyOrNull(key)?.getString()?.toBooleanStrict() ?: default
            return AtlasConfiguration(
                apiEnabled = enabled("atlas.api.enabled"),
                gitEnabled = enabled("atlas.git.enabled"),
                artifactsEnabled = enabled("atlas.artifacts.enabled"),
                uiEnabled = enabled("atlas.ui.enabled"),
                runnerEnabled = enabled("atlas.runner.enabled"),
                runnerConcurrency = config.propertyOrNull("atlas.runner.concurrency")?.getString()?.toInt() ?: 16,
                runnerQueues = config.propertyOrNull("atlas.runner.queues")?.getList()
                    ?.flatMap { it.split(',') }?.map(String::trim)?.filter(String::isNotEmpty)
                    ?: listOf("git", "pipelines", "common"),
                runnerListeners = enabled("atlas.runner.listeners"),
            )
        }
    }
}
