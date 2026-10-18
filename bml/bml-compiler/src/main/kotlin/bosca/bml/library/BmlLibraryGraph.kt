package bosca.bml.library

/** Validated compiled-library ownership and dependency edges shared by import consumers. */
class BmlLibraryGraph(dependencies: List<BmlLibraryDependency>) {
    /** A component retains its producer contract even when reached through another library. */
    data class Component(val dependency: BmlLibraryDependency, val component: BmlLibraryComponent) {
        val id: String = BmlLibraryArtifacts.componentId(dependency.manifest.library, component.tag)
    }

    private val libraries = dependencies.associateBy { it.manifest.library }
    private val components = dependencies.flatMap { dependency ->
        dependency.manifest.components.map { Component(dependency, it) }
    }.associateBy { it.id }

    init {
        require(libraries.size == dependencies.size) { "Resolve duplicate BML dependencies before building the library graph" }
        dependencies.forEach { it.manifest.validate() }
        val packages = mutableMapOf<String, BmlLibraryDependency>()
        val renderers = mutableMapOf<String, Component>()
        dependencies.forEach { dependency ->
            val previous = packages.putIfAbsent(dependency.manifest.generatedPackage, dependency)
            require(previous == null) {
                "BML generated package '${dependency.manifest.generatedPackage}' is shared by " +
                    "${previous?.manifest?.library} in ${previous?.artifact} and ${dependency.manifest.library} in ${dependency.artifact}"
            }
        }
        components.values.forEach { component ->
            val previous = renderers.putIfAbsent(component.component.renderer, component)
            require(previous == null) {
                "BML renderer '${component.component.renderer}' is shared by " +
                    "${previous?.id} in ${previous?.dependency?.artifact} and ${component.id} in ${component.dependency.artifact}"
            }
        }
        val visited = mutableSetOf<String>()
        val path = linkedSetOf<String>()
        fun visit(library: String) {
            val owner = libraries.getValue(library)
            require(library !in path) {
                "BML library dependency cycle: " + (path.toList() + library).joinToString(" -> ") {
                    "$it (${libraries.getValue(it).artifact})"
                }
            }
            if (!visited.add(library)) return
            path += library
            owner.manifest.requirements.forEach { requirement ->
                require(requirement.library in libraries) {
                    "Missing BML dependency ${requirement.library}:${requirement.version} required by " +
                        "$library in ${owner.artifact}"
                }
                visit(requirement.library)
            }
            path.remove(library)
        }
        libraries.keys.sorted().forEach(::visit)
        dependencies.forEach { owner ->
            owner.manifest.requirements.forEach { requirement ->
                val resolved = libraries.getValue(requirement.library)
                val context = "${owner.manifest.library} in ${owner.artifact} requires ${requirement.library}"
                require(resolved.manifest.version == requirement.version) {
                    "$context:${requirement.version}, but resolved ${resolved.manifest.version} in ${resolved.artifact}"
                }
                requirement.components.forEach { tag ->
                    require(resolved.manifest.components.any { it.tag == tag && it.exported }) {
                        "$context export <$tag>, which is missing or private in ${resolved.artifact}"
                    }
                }
                require(BmlLibraryArtifacts.digest(resolved.manifest) == requirement.digest) {
                    "$context contract ${requirement.digest}, but ${resolved.artifact} has a different compiled contract; rebuild the consuming library"
                }
            }
        }
    }

    /** Full or eager reachable components, deduplicated by canonical identity and retaining ownership. */
    fun closure(library: String, tag: String, eager: Boolean = false): List<Component> {
        val visited = linkedSetOf<String>()
        fun visit(id: String) {
            if (!visited.add(id)) return
            val component = components.getValue(id).component
            (if (eager) component.eagerDependencies else component.dependencies).forEach(::visit)
        }
        visit(BmlLibraryArtifacts.componentId(library, tag))
        return visited.map(components::getValue)
    }
}
