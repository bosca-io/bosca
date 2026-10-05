package bosca.cli.ci

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AgentConfig(
    val agentId: String,
    val token: String,
    val serverUrl: String,
    val registryUrl: String = "",
    val name: String = "",
    val labels: List<String> = emptyList(),
    val mode: String = "runner",
    val pollIntervalSeconds: Int = 5,
    val workDir: String = "/tmp/bosca-ci",
    val providerApiToken: String? = null,
    val selfDestructToken: String? = null,
) {
    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val configFile: File
            get() = File(System.getProperty("user.home"), ".bosca/agent.json")

        fun load(): AgentConfig? {
            if (!configFile.exists()) return null
            return try {
                json.decodeFromString<AgentConfig>(configFile.readText())
            } catch (_: Exception) {
                null
            }
        }

        fun save(config: AgentConfig) {
            configFile.parentFile.mkdirs()
            configFile.writeText(json.encodeToString(config))
            configFile.setReadable(false, false)
            configFile.setReadable(true, true)
            configFile.setWritable(false, false)
            configFile.setWritable(true, true)
        }
    }
}
