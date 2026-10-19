package bosca.bml.library

import bosca.bml.codegen.BmlComponentMeta
import bosca.bml.codegen.BmlMetricsManifest
import bosca.bml.codegen.BmlPageMeta
import bosca.bml.project.ContentDigest
import java.io.File
import java.util.jar.JarFile
import kotlinx.serialization.json.JsonPrimitive

/** Links only reachable binary component entries into an application's existing client build. */
object BmlLibraryClientLinker {
    /** Producer-owned metadata with application-unique module names, matching the runtime registry. */
    fun metadata(component: BmlLibraryGraph.Component): BmlComponentMeta = component.component.let {
        BmlComponentMeta(
            component.id, it.styles, it.dependencies, moduleName(component), it.eagerDependencies,
            it.hasServerState, it.hasEagerFeatureFlags, it.hasDeferredIslands,
        )
    }

    /** The alias is independent of checkout paths and cannot collide with another producer's module. */
    fun moduleName(component: BmlLibraryGraph.Component): String? = component.component.clientModule?.let {
        BmlLibraryClientManifest.moduleName(component.dependency.manifest.library, component.dependency.manifest.version, it)
    }

    /** Extracts verified resources and emits relative ESM bridges; producer source is never consulted. */
    fun link(
        selected: List<BmlLibraryGraph.Component>,
        pages: List<BmlPageMeta>,
        components: List<BmlComponentMeta>,
        tsDirectory: File,
        resourceDirectory: File,
        libraries: List<BmlLibraryDependency>,
    ): LinkedBmlClientAssets {
        val clientMarker = File(tsDirectory, "bml-linked-client")
        clientMarker.delete()
        val inputDirectory = File(tsDirectory, "libraries")
        require(inputDirectory.deleteRecursively()) { "Cannot clear linked BML client inputs" }
        require(File(resourceDirectory, "libraries").deleteRecursively()) { "Cannot clear linked BML resources" }
        val dependencies = libraries.associateBy { it.manifest.library }
        val manifests = linkedMapOf<String, BmlLibraryClientManifest>()
        val seen = mutableSetOf<String>()
        fun visit(library: String) {
            if (!seen.add(library)) return
            val dependency = dependencies.getValue(library)
            dependency.manifest.requirements.map { it.library }.filter { it in dependencies }.sorted().forEach(::visit)
            fun process(read: (String) -> ByteArray?) {
                val bytes = read(BmlLibraryClientManifest.PATH)
                if (bytes == null) {
                    require(selected.none { it.dependency == dependency && it.component.clientModule != null }) {
                        "Missing BML client manifest in ${dependency.artifact}; build the library's client assets before publication"
                    }
                    return
                }
                val manifest = BmlLibraryClientManifest.decode(bytes.decodeToString())
                manifest.validate(library, dependency.manifest.version)
                manifests[library] = manifest
                val key = BmlLibraryClientManifest.namespace(library, manifest.version)
                for (entry in manifest.files) {
                    val content = requireNotNull(read("${manifest.resourcePrefix}/${entry.path}")) {
                        "Missing BML library resource ${entry.path} in ${dependency.artifact}"
                    }
                    require(ContentDigest.sha256(content) == entry.digest) { "BML library resource digest mismatch: ${entry.path} in ${dependency.artifact}" }
                    File(inputDirectory, "$key/${entry.path}").apply { parentFile.mkdirs(); writeBytes(content) }
                    // Producer ESM is a bundler input. Only the selected, rebundled application
                    // entries are served; declarations are compiler inputs, not browser resources.
                    if (!entry.path.endsWith(".d.ts") &&
                        (entry.path.startsWith("public/") || entry.path.substringAfterLast('.') !in setOf("js", "mjs"))) {
                        File(resourceDirectory, "libraries/$key/${entry.path}").apply { parentFile.mkdirs(); writeBytes(content) }
                    }
                }
                selected.filter { it.dependency == dependency }.mapNotNull { it.component.clientModule }.distinct().forEach { module ->
                    val entry = requireNotNull(manifest.modules[module]) { "Missing BML client module $module in ${dependency.artifact}" }
                    val name = BmlLibraryClientManifest.moduleName(library, manifest.version, module).removeSuffix(".js")
                    File(tsDirectory, "$name.ts").writeText("import ${quote("./libraries/$key/${entry.path}")}\n")
                }
            }
            if (dependency.artifact.isDirectory) {
                process { path -> File(dependency.artifact, path).takeIf { it.isFile }?.readBytes() }
            } else JarFile(dependency.artifact).use { jar ->
                process { path -> jar.getJarEntry(path)?.let { jar.getInputStream(it).use { stream -> stream.readBytes() } } }
            }
        }
        selected.map { it.dependency.manifest.library }.distinct().sorted().forEach(::visit)
        val byId = selected.associateBy { it.id }
        val byTag = components.associateBy { it.tag }
        val styles = linkedMapOf<String, List<String>>()
        val scripts = linkedMapOf<String, List<String>>()
        for (page in pages) {
            val closure = BmlMetricsManifest.closure(page.componentTags, byTag)
            val used = closure.mapNotNull(byId::get)
            val owners = mutableSetOf<String>()
            fun include(library: String) {
                if (!owners.add(library)) return
                dependencies.getValue(library).manifest.requirements.forEach { include(it.library) }
            }
            used.forEach { include(it.dependency.manifest.library) }
            val pageManifests = manifests.values.filter { it.library in owners }
            styles[page.route] = pageManifests.flatMap { manifest ->
                (manifest.globalStyles + used.filter { it.dependency.manifest.library == manifest.library }
                    .flatMap { component -> manifest.modules[component.component.clientModule]?.styles.orEmpty() })
                    .distinct().map { "${manifest.urlPrefix}/$it" }
            }.distinct()
            val imports = pageManifests.flatMap { manifest ->
                manifest.globalScripts.map { "./libraries/${BmlLibraryClientManifest.namespace(manifest.library, manifest.version)}/$it" }
            }
            if (imports.isNotEmpty()) {
                val name = "BmlLibraries_${page.objectName}"
                File(tsDirectory, "$name.ts").writeText(imports.joinToString("\n", postfix = "\n") { "import ${quote(it)}" })
                scripts[page.route] = listOf("$name.js")
            }
        }
        if (scripts.isNotEmpty() || selected.any { it.component.clientModule != null }) clientMarker.writeText("1\n")
        return LinkedBmlClientAssets(styles, scripts)
    }

    private fun quote(value: String): String = JsonPrimitive(value).toString()
}
