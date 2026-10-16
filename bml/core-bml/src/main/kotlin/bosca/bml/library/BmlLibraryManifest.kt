package bosca.bml.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Build-time metadata shipped alongside compiled BML component renderers. */
@Serializable
data class BmlLibraryManifest(
    val formatVersion: Int,
    val library: String,
    val version: String,
    val generatedPackage: String,
    val compilerVersion: String,
    val components: List<BmlLibraryComponent>,
) {
    /** Validates metadata before publication or use by compiler and editor tooling. */
    fun validate() {
        require(formatVersion == BmlLibraryArtifacts.FORMAT_VERSION) {
            "Unsupported BML library manifest version $formatVersion for $library"
        }
        require(BmlLibraryArtifacts.isLibraryId(library)) { "Invalid BML library identity '$library'; expected group:artifact" }
        require(version.isNotBlank() && version.none(Char::isISOControl)) { "Missing or invalid artifact version for $library" }
        require(BmlLibraryArtifacts.isPackageName(generatedPackage)) { "Invalid BML generated package '$generatedPackage'" }
        require(compilerVersion.isNotBlank()) { "Missing BML compiler version for $library" }
        require(components.map { it.tag }.distinct().size == components.size) { "Duplicate component tags in $library" }
        require(components.map { it.renderer }.distinct().size == components.size) { "Duplicate component renderers in $library" }
        require(components.any { it.exported }) { "Library $library has no exported components" }
        val tags = components.map { it.tag }.toSet()
        components.forEach { component ->
            require(BmlLibraryArtifacts.isComponentTag(component.tag)) { "Invalid component tag '${component.tag}' in $library" }
            require(component.renderer.startsWith("$generatedPackage.") && BmlLibraryArtifacts.isPackageName(component.renderer)) {
                "Invalid renderer '${component.renderer}' in $library"
            }
            require(component.source.entry.startsWith("bml/") && component.source.entry.endsWith(".bml") &&
                component.source.entry.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                '\\' !in component.source.entry && component.source.entry.none(Char::isISOControl)) {
                "Invalid BML source entry '${component.source.entry}' in $library"
            }
            require(component.source.line > 0 && component.source.column > 0) { "Invalid source location for ${component.tag}" }
            require(Regex("sha256:[0-9a-f]{64}").matches(component.source.digest)) { "Invalid source digest for ${component.tag}" }
            require(component.props.map { it.name }.distinct().size == component.props.size) { "Duplicate props for ${component.tag}" }
            require(component.props.all { it.name.isNotBlank() && it.declaredType.isNotBlank() }) { "Invalid props for ${component.tag}" }
            require(component.dependencies.all { it in tags }) { "Missing component dependency for ${component.tag} in $library" }
            require(component.eagerDependencies.all { it in component.dependencies }) { "Invalid eager dependencies for ${component.tag}" }
        }
        val byTag = components.associateBy { it.tag }
        val visited = mutableSetOf<String>()
        val path = linkedSetOf<String>()
        fun visit(tag: String) {
            require(tag !in path) { "Component dependency cycle in $library: ${(path.toList() + tag).joinToString(" -> ")}" }
            if (!visited.add(tag)) return
            path.add(tag)
            byTag.getValue(tag).dependencies.forEach(::visit)
            path.remove(tag)
        }
        tags.forEach(::visit)
    }
}

/** A component's compiled entry point, authoring contract, and dependency metadata. */
@Serializable
data class BmlLibraryComponent(
    val tag: String,
    val exported: Boolean,
    val renderer: String,
    val source: BmlLibrarySource,
    val props: List<BmlLibraryProp>,
    val hasDefaultSlot: Boolean,
    val dependencies: List<String>,
    val eagerDependencies: List<String>,
    val styles: String,
    val clientModule: String?,
    val hasServerState: Boolean,
    val hasClientBehavior: Boolean,
    val hasDeferredIslands: Boolean,
    val hasEagerFeatureFlags: Boolean,
    val renderRevision: String,
)

/** Original BML declaration in the matching sources artifact; positions are one-based. */
@Serializable
data class BmlLibrarySource(val entry: String, val line: Int, val column: Int, val digest: String)

/** Authored Kotlin type and default presence; default expressions execute in the compiled producer. */
@Serializable
data class BmlLibraryProp(val name: String, val declaredType: String, val hasDefault: Boolean)

/** Artifact locations, naming rules, and explicit manifest serialization shared by BML tooling. */
object BmlLibraryArtifacts {
    const val MANIFEST_PATH: String = "META-INF/bml/library-manifest.json"
    const val FORMAT_VERSION: Int = 1
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
        "import", "page", "route", "message", "email", "push", "template", "layout", "component", "prop",
        "slot", "data", "inject", "island", "fallback", "script", "style", "contract", "for", "if", "else",
        "else-if", "t",
    )

    /** Whether [value] is a version-free Maven group:artifact identity. */
    fun isLibraryId(value: String): Boolean = value.split(':').let { it.size == 2 && it.all(coordinatePart::matches) }

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
}
