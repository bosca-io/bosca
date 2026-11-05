package bosca.cli.harness

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Generation-owned runtime secret fields; `BOSCA_EGRESS_TOKEN` is present only for runtimes with an egress proxy. */
internal val RUNTIME_SECRET_KEYS = setOf("BOSCA_TOKEN", "BOSCA_RUNTIME_TOKEN", "BOSCA_RUNTIME_EXPIRES", "BOSCA_EGRESS_TOKEN")

/** Docker Secrets supply generation defaults in memory; explicit process credentials take precedence. */
internal fun harnessEnvironment(base: Map<String, String>, directory: Path = Path.of("/run/secrets")): Map<String, String> {
    val serializer = MapSerializer(String.serializer(), String.serializer())
    val bootstrap = directory.resolve("bosca-runtime.json")
    val owned = if (bootstrap.exists()) Json.decodeFromString(serializer, bootstrap.readText()) else emptyMap()
    require(owned.keys.all { it in RUNTIME_SECRET_KEYS }) { "Invalid runtime secret fields" }
    val references = base["BOSCA_SECRET_FILES"]?.let { Json.decodeFromString(serializer, it) }.orEmpty()
    val provider = references.mapValues { (name, file) ->
        require(name.matches(Regex("[A-Z][A-Z0-9_]*")) && !name.startsWith("BOSCA_")) { "Invalid provider secret environment" }
        require(file.matches(Regex("[a-zA-Z0-9_.-]+")) && file !in setOf(".", "..", "bosca-runtime.json")) { "Invalid provider secret path" }
        directory.resolve(file).readText().trimEnd('\n', '\r')
    }
    return owned + base + provider
}
