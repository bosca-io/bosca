package bosca.cli.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText

/** Package metadata read from the immutable runtime installation, independent of deployment expectations. */
@Serializable
internal data class HarnessPackage(val name: String, val version: String) {
    companion object {
        fun version(installation: Path, name: String): String {
            val value = Json { ignoreUnknownKeys = true }.decodeFromString(serializer(),
                installation.resolve("node_modules/$name/package.json").readText())
            require(value.name == name && value.version.isNotBlank()) { "Invalid installed harness package: $name" }
            return value.version
        }
    }
}
