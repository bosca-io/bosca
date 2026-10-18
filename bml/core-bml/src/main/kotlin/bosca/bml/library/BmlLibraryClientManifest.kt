package bosca.bml.library

import bosca.bml.project.ContentDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Producer asset contract, independent of producer source paths and npm installations. */
@Serializable
data class BmlLibraryClientManifest(
    val formatVersion: Int,
    val library: String,
    val version: String,
    val resourcePrefix: String,
    val urlPrefix: String,
    val modules: Map<String, BmlLibraryClientModule>,
    val globalScripts: List<String>,
    val globalStyles: List<String>,
    val files: List<BmlAssetFile>,
) {
    /** Rejects incompatible identities, unsafe paths, and entries absent from the file inventory. */
    fun validate(expectedLibrary: String, expectedVersion: String) {
        require(formatVersion == 1) { "Unsupported BML client manifest version $formatVersion" }
        require(library == expectedLibrary && version == expectedVersion) { "BML client artifact identity mismatch: $library:$version" }
        val key = namespace(library, version)
        require(resourcePrefix == "bml/libraries/$key" && urlPrefix == "/_bml/libraries/$key") { "Invalid BML client resource namespace" }
        BmlApplicationAssets.validateFiles(files)
        val paths = files.map { it.path }.toSet()
        require((globalScripts + globalStyles + modules.values.flatMap { listOf(it.path) + it.styles }).all { it in paths }) {
            "BML client entry is missing from the resource inventory"
        }
        require(modules.keys.all { Regex("[A-Za-z_][A-Za-z0-9_]*\\.js").matches(it) }) { "Invalid BML client module name" }
    }

    companion object {
        const val PATH: String = "META-INF/bml/client-manifest.json"
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads the typed producer contract without reflection-based serializer lookup. */
        fun decode(text: String): BmlLibraryClientManifest = json.decodeFromString(serializer(), text)

        /** Mirrors the browser bundler's SHA-256 of the UTF-8 JSON coordinate/version array. */
        fun namespace(library: String, version: String): String = ContentDigest.sha256(
            JsonArray(listOf(JsonPrimitive(library), JsonPrimitive(version))).toString(),
        ).removePrefix("sha256:")

        /** Collision-free flat module name used by the consuming application's bundle. */
        fun moduleName(library: String, version: String, module: String): String = "L${namespace(library, version)}_$module"
    }
}
