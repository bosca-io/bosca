package bosca.bml.library

import kotlinx.serialization.Serializable

/** Build-time metadata shipped alongside compiled BML component renderers. */
@Serializable
data class BmlLibraryManifest @JvmOverloads constructor(
    val formatVersion: Int,
    val library: String,
    val version: String,
    val generatedPackage: String,
    val compilerVersion: String,
    val components: List<BmlLibraryComponent>,
    val requirements: List<BmlLibraryRequirement> = emptyList(),
    /** Public, zero-argument ProviderRegistrar classes owned by this artifact. */
    val providerRegistrars: List<String> = emptyList(),
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
        require(providerRegistrars.distinct().size == providerRegistrars.size &&
            providerRegistrars.all { '.' in it && BmlLibraryArtifacts.isPackageName(it) }) {
            "Invalid or duplicate provider registrar in $library"
        }
        require(components.map { it.tag }.distinct().size == components.size) { "Duplicate component tags in $library" }
        require(components.map { it.renderer }.distinct().size == components.size) { "Duplicate component renderers in $library" }
        require(components.any { it.exported }) { "Library $library has no exported components" }
        require(requirements.map { it.library }.distinct().size == requirements.size) { "Duplicate library requirements in $library" }
        requirements.forEach { requirement ->
            require(BmlLibraryArtifacts.isLibraryId(requirement.library) && requirement.library != library) {
                "Invalid library requirement '${requirement.library}' in $library"
            }
            require(requirement.version.isNotBlank() && requirement.version.none(Char::isISOControl)) {
                "Missing or invalid required version for ${requirement.library} in $library"
            }
            require(Regex("sha256:[0-9a-f]{64}").matches(requirement.digest)) {
                "Invalid contract digest for ${requirement.library} in $library"
            }
            require(requirement.components.isNotEmpty() && requirement.components.distinct().size == requirement.components.size &&
                requirement.components.all(BmlLibraryArtifacts::isComponentTag)) {
                "Invalid required component exports for ${requirement.library} in $library"
            }
        }
        val localIds = components.associateBy { BmlLibraryArtifacts.componentId(library, it.tag) }
        val dependencyIds = localIds.keys + requirements.flatMap { requirement ->
            requirement.components.map { BmlLibraryArtifacts.componentId(requirement.library, it) }
        }
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
            require(component.dependencies.all { it in dependencyIds }) { "Missing component dependency for ${component.tag} in $library" }
            require(component.eagerDependencies.all { it in component.dependencies }) { "Invalid eager dependencies for ${component.tag}" }
        }
        val visited = mutableSetOf<String>()
        val path = linkedSetOf<String>()
        fun visit(tag: String) {
            require(tag !in path) { "Component dependency cycle in $library: ${(path.toList() + tag).joinToString(" -> ")}" }
            if (!visited.add(tag)) return
            path.add(tag)
            localIds.getValue(tag).dependencies.filter { it in localIds }.forEach(::visit)
            path.remove(tag)
        }
        localIds.keys.forEach(::visit)
    }
}
