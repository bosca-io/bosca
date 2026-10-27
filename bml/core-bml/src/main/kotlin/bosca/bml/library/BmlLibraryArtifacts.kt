package bosca.bml.library

import bosca.bml.project.ContentDigest
import kotlinx.serialization.json.Json

/** Artifact locations, naming rules, and explicit manifest serialization shared by BML tooling. */
object BmlLibraryArtifacts {
    const val MANIFEST_PATH: String = "META-INF/bml/library-manifest.json"
    const val FORMAT_VERSION: Int = 5
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    private val coordinatePart = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val keywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
        "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
        "typeof", "val", "var", "when", "while",
    )
    /** Syntax reserved by BML cannot be exported as an overridable component. */
    val protectedTags: Set<String> = setOf(
        "import", "page", "route", "message", "email", "push", "subject", "template", "layout", "use", "component", "prop",
        "slot", "data", "inject", "island", "fallback", "script", "style", "contract", "for", "if", "else",
        "else-if", "t",
    )

    /** Whether [value] is a version-free Maven group:artifact identity. */
    fun isLibraryId(value: String): Boolean = value.split(':').let { it.size == 2 && it.all(coordinatePart::matches) }

    /**
     * Stable DOM, CSS, and route-safe identity for a compiled library component. The coordinate is
     * encoded without truncation; versions, import aliases, and checkout locations never enter it.
     */
    fun componentId(library: String, tag: String): String {
        require(isLibraryId(library)) { "Invalid BML library identity '$library'" }
        require(isComponentTag(tag)) { "Invalid BML component tag '$tag'" }
        return "bml-" + library.encodeToByteArray().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        } + "-$tag"
    }

    /** Whether [value] is an unescaped Kotlin package or qualified declaration name. */
    fun isPackageName(value: String): Boolean = value.split('.').all { identifier.matches(it) && it !in keywords && it != "_" }

    /** Whether [value] can name an ordinary component without replacing BML syntax. */
    fun isComponentTag(value: String): Boolean = Regex("[a-z][a-z0-9-]*").matches(value) && value !in protectedTags

    /** Writes a validated manifest without reflection-based serializer lookup. */
    fun encode(manifest: BmlLibraryManifest): String {
        manifest.validate()
        return json.encodeToString(BmlLibraryManifest.serializer(), manifest)
    }

    /** Reads a supported manifest and rejects malformed metadata before symbol resolution. */
    fun decode(source: String): BmlLibraryManifest =
        json.decodeFromString(BmlLibraryManifest.serializer(), source).also { it.validate() }

    /** Digest of the validated, canonical metadata expected by a compiled library consumer. */
    fun digest(manifest: BmlLibraryManifest): String = ContentDigest.sha256(encode(manifest))
}
