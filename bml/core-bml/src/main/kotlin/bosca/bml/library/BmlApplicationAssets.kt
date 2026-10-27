package bosca.bml.library

import bosca.bml.project.ContentDigest
import kotlinx.serialization.json.Json

/** Direct resource reader emitted by the compiler; only indexed, digest-checked files can be served. */
class BmlApplicationAssets(
    private val resourcePrefix: String,
    val stylesByRoute: Map<String, List<String>>,
    val scriptsByRoute: Map<String, List<String>>,
    private val resource: (String) -> ByteArray?,
) {
    private val manifestBytes by lazy { resource("$resourcePrefix/client-manifest.json") }
    private val files by lazy {
        manifestBytes?.let { bytes ->
            val manifest = Json.decodeFromString(BmlClientManifest.serializer(), bytes.decodeToString())
            require(manifest.formatVersion == 1) { "Unsupported BML application asset format ${manifest.formatVersion}" }
            validateFiles(manifest.files)
            manifest.files.associateBy { it.path }
        }.orEmpty()
    }

    /** Includes every packaged JS, CSS, and binary resource in the deployment validator. */
    val revision: String by lazy { manifestBytes?.let(ContentDigest::sha256).orEmpty() }

    /** Returns the indexed content validator without loading a body; unsafe and unknown paths return null. */
    fun digest(path: String): String? = if (isSafePath(path)) files[path]?.digest else null

    /** Reads an indexed relative path, or returns null for an unknown or unsafe request. */
    fun read(path: String): ByteArray? {
        if (!isSafePath(path)) return null
        val entry = files[path] ?: return null
        val bytes = requireNotNull(resource("$resourcePrefix/$path")) { "Missing packaged BML asset $path" }
        require(ContentDigest.sha256(bytes) == entry.digest) { "BML asset digest mismatch: $path" }
        return bytes
    }

    companion object {
        /** Resource paths cannot escape their package root or contain ambiguous URL separators. */
        fun isSafePath(path: String): Boolean = path.isNotEmpty() &&
            path.none { it == '\\' || it == '?' || it == '#' || it == '%' || it.isISOControl() } &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

        /** Checks path uniqueness and the content-addressing contract before reading any resource. */
        fun validateFiles(files: List<BmlAssetFile>) {
            require(files.map { it.path }.distinct().size == files.size) { "Duplicate BML asset path" }
            require(files.all { isSafePath(it.path) && Regex("sha256:[a-f0-9]{64}").matches(it.digest) }) { "Invalid BML asset inventory" }
        }
    }
}
