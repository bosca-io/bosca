package bosca.atlas.ui

import bosca.design.model.SubsystemNavigationItem

/** Atlas's available subsystems, using Studio's names, descriptions, icons, and accents. */
object AtlasSubsystems {
    val items = listOf(
        SubsystemNavigationItem("git", "Git", "Browse repositories, review pull requests, and search code", "branch", "#64748b", "/repositories", "Build & Deliver"),
        SubsystemNavigationItem("artifacts", "Artifacts", "Publish and distribute packages across registries", "package", "#f764f6", "/artifacts/namespaces", "Build & Deliver"),
    )
}
