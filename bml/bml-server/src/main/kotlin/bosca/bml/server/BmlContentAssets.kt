package bosca.bml.server

import bosca.bml.library.BmlApplicationAssets
import bosca.bml.project.ContentDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** Generation-local lookup of immutable asset URLs; only indexed resources are exposed. */
internal class BmlContentAssets(
    sources: List<BmlApplicationAssets>,
    clientDir: File?,
    memory: Map<String, String>,
) {
    val urls = linkedMapOf<String, String>()
    val bodies = linkedMapOf<String, ByteArray>()
    val packagedPaths = linkedMapOf<String, String>()
    val entries: Map<String, String>

    init {
        val manifests = sources.mapNotNull { it.read("prod/bml-imports.json")?.decodeToString() } +
            listOfNotNull(clientDir?.resolve("bml-imports.json")?.takeIf { it.isFile }?.readText())
        entries = manifests.flatMap { manifest ->
            Json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), manifest)
                .map { (logical, closure) ->
                    val entry = requireNotNull(closure.firstOrNull()) { "Missing production entry: $logical" }
                    require(bosca.bml.library.BmlApplicationAssets.isSafePath(entry)) { "Invalid production entry: $entry" }
                    logical to entry
                }
        }.toMap()
        for (source in sources) for (path in source.paths.filter {
            (it.startsWith("libraries/") || it.startsWith("global/")) && !it.endsWith(".css") && !it.endsWith(".js")
        }) {
            val original = "/_bml/$path"
            val url = BmlAssetUrls.content("/_bml/content/${path.substringAfterLast('/')}", requireNotNull(source.digest(path)))
            urls[original] = url
            packagedPaths[url] = path
        }
        for (source in sources) for (path in source.paths.filter { it.startsWith("libraries/") && it.endsWith(".css") }) {
            val original = "/_bml/$path"
            val css = BmlAssetUrls.libraryCss(requireNotNull(source.read(path)).decodeToString(), urls)
            add(original, css)
        }
        memory.forEach { (url, body) -> add(url, if (url.endsWith(".css")) BmlAssetUrls.libraryCss(body, urls) else body) }
    }

    private fun add(original: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val base = "/_bml/content/${original.substringAfterLast('/')}"
        val url = BmlAssetUrls.content(base, ContentDigest.sha256(bytes))
        urls[original] = url
        bodies[url] = bytes
    }

    fun url(original: String): String = urls[original] ?: original
}
