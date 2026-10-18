package bosca.bml.codegen

import bosca.bml.library.BmlLibraryArtifacts
import bosca.bml.library.BmlLibraryComponent
import bosca.bml.library.BmlLibraryManifest
import bosca.bml.library.BmlLibraryProp
import bosca.bml.library.BmlImportResolver
import bosca.bml.library.BmlLibraryRequirement
import bosca.bml.library.BmlLibrarySource
import bosca.bml.parser.AttrText
import bosca.bml.parser.BoundAttribute
import bosca.bml.parser.ElementNode
import bosca.bml.parser.ForNode
import bosca.bml.parser.IfNode
import bosca.bml.parser.Node
import bosca.bml.parser.StaticAttribute

/** Combines generated component metadata with original declarations for a producer artifact. */
internal object BmlLibraryManifestGenerator {
    data class Declaration(val element: ElementNode, val source: BmlLibrarySource, val revision: String)

    fun generate(
        library: String,
        version: String,
        packageName: String,
        exports: Set<String>,
        declarations: Map<String, Declaration>,
        metadata: List<BmlComponentMeta>,
        clientBehavior: Set<String>,
        imports: List<BmlImportResolver.Binding> = emptyList(),
    ): BmlLibraryManifest = BmlLibraryManifest(
        formatVersion = BmlLibraryArtifacts.FORMAT_VERSION,
        library = library,
        version = version,
        generatedPackage = packageName,
        compilerVersion = BmlCompilerRevision.current,
        requirements = imports.groupBy { it.dependency.manifest.library }.toSortedMap().map { (_, bindings) ->
            val dependency = bindings.first().dependency.manifest
            BmlLibraryRequirement(
                dependency.library, dependency.version, BmlLibraryArtifacts.digest(dependency),
                bindings.map { it.component.tag }.distinct().sorted(),
            )
        },
        components = metadata.sortedBy { it.tag }.map { meta ->
            val declaration = declarations.getValue(meta.tag)
            BmlLibraryComponent(
                tag = meta.tag,
                exported = meta.tag in exports,
                renderer = "$packageName.${BmlCodeGenerator.componentObjectName(meta.tag)}",
                source = declaration.source,
                props = declaration.element.children.filterIsInstance<ElementNode>()
                    .filter { it.namespace == null && it.name == "prop" }
                    .map { prop ->
                        BmlLibraryProp(
                            name = requireNotNull(literal(prop, "name")) { "Missing prop name in ${meta.tag}" },
                            declaredType = literal(prop, "type") ?: "Any?",
                            hasDefault = prop.attributes.any {
                                it.name == "default" && (it is BoundAttribute || literal(prop, "default") != null)
                            },
                        )
                    },
                hasDefaultSlot = containsSlot(declaration.element.children),
                dependencies = meta.deps,
                eagerDependencies = meta.eagerDeps,
                styles = meta.styles,
                clientModule = meta.clientModule,
                hasServerState = meta.hasServerState,
                hasClientBehavior = meta.tag in clientBehavior,
                hasDeferredIslands = meta.hasDeferredIslands,
                hasEagerFeatureFlags = meta.hasEagerFeatureFlags,
                renderRevision = declaration.revision,
            )
        },
    )

    private fun literal(element: ElementNode, name: String): String? =
        element.attributes.filterIsInstance<StaticAttribute>().firstOrNull { it.name == name }?.value
            ?.takeIf { it.all { part -> part is AttrText } }?.joinToString("") { (it as AttrText).value }

    private fun containsSlot(nodes: List<Node>): Boolean = nodes.any { node ->
        when (node) {
            is ElementNode -> node.namespace == null && node.name == "slot" || containsSlot(node.children)
            is ForNode -> containsSlot(node.children)
            is IfNode -> node.branches.any { containsSlot(it.children) } || containsSlot(node.elseChildren.orEmpty())
            else -> false
        }
    }
}
