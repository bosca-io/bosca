package bosca.bml.codegen

import bosca.bml.library.BmlLibraryGraph

/** The existing catalog contract, composed from canonical reachable binary components. */
internal object BmlLibraryMessages {
    fun entries(components: List<BmlLibraryGraph.Component>): List<BmlI18nEntry> =
        components.distinctBy { it.id }.sortedBy { it.id }.flatMap { owner ->
            val manifest = owner.dependency.manifest
            owner.component.messages.map { entry ->
                BmlI18nEntry(
                    key = entry.key,
                    message = entry.message,
                    pluralForms = entry.pluralForms,
                    placeholders = entry.placeholders.map { (name, expression) -> BmlI18nPlaceholder(name, expression) },
                    origin = BmlI18nOrigin.valueOf(entry.origin),
                    file = "${manifest.library}:${manifest.version}!/${owner.component.source.entry}",
                    line = entry.line,
                )
            }
        }
}
