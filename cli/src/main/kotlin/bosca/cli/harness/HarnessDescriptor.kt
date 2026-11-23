package bosca.cli.harness

import bosca.ai.kit.harness.RuntimeVersions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText

/** Image-owned compatibility declaration, verified against the installed packages before serving. */
@Serializable
internal data class HarnessDescriptor(
    val transportVersion: Int,
    val acpVersion: Int,
    val archiveFormatVersion: Int,
    val statePolicy: String,
    val adapter: String,
    val harness: String,
    val paths: Map<String, String>,
) {
    fun versions(installation: Path, cli: String): RuntimeVersions {
        require(transportVersion == 1 && acpVersion == 1 && archiveFormatVersion == 1) { "Unsupported runtime compatibility version" }
        require(paths == mapOf("workspace" to "/workspace", "harness" to "/state/harness", "scratch" to "/state/scratch")) { "Unsupported runtime mount layout" }
        val family = HarnessArchive.family(statePolicy)
        require(harness.substringBeforeLast('@') == when (family) {
            "codex" -> "@openai/codex"
            else -> "@anthropic-ai/claude-agent-sdk"
        }) { "Harness state directory belongs to another family" }
        fun installed(reference: String): String {
            val separator = reference.lastIndexOf('@')
            require(separator > 0 && separator < reference.lastIndex) { "Harness packages must include an exact version" }
            val name = reference.substring(0, separator)
            require(name.matches(Regex("(?:@[a-z0-9-]+/)?[a-z0-9-]+"))) { "Invalid harness package name" }
            val version = HarnessPackage.version(installation, name)
            require(version == reference.substring(separator + 1)) { "Installed harness package differs from compatibility declaration: $name" }
            return version
        }
        return RuntimeVersions(cli, installed(adapter), installed(harness), statePolicy)
    }

    companion object {
        fun read(installation: Path): HarnessDescriptor = Json.decodeFromString(serializer(), installation.resolve("compatibility.json").readText())
    }
}
