package bosca.ai.installer

import bosca.content.metadata.model.MemoryAttributes
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.trait.model.TraitInput
import bosca.trait.service.TraitService

/** Memory documents use the ordinary content editor and lifecycle. */
class MemoryInstaller(private val traits: TraitService) : PackageInstaller {
    override val version = "1.0.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        if (traits.get(MemoryAttributes.TRAIT) == null) traits.add(TraitInput(
            id = MemoryAttributes.TRAIT, name = "Memory", description = "Explicitly saved preferences, facts, and decisions",
            deleteWorkflowId = null, workflowIds = emptyList(), contentTypes = listOf(MemoryAttributes.CONTENT_TYPE),
        ))
    }
}
