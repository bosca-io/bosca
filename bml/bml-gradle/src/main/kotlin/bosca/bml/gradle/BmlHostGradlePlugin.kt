package bosca.bml.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

/** Supplies deployment identity to application hosts without enabling BML compilation or bundling. */
class BmlHostGradlePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.plugins.withId("application") {
            val outputDir = target.layout.buildDirectory.dir("generated/bml/build-id")
            val buildId = target.tasks.register("bmlBuildId", BmlBuildIdTask::class.java) { task ->
                task.group = "bml"
                task.description = "Write the BML build identity (the build time) unless it already exists."
                task.identityFile.set(outputDir.map { it.file(BUILD_ID_RESOURCE) })
            }
            target.extensions.getByType(SourceSetContainer::class.java)
                .getByName("main").resources.srcDir(buildId.map { outputDir.get() })
        }
    }
}

/** Classpath resource holding the application build identity read by bml-server. */
internal const val BUILD_ID_RESOURCE: String = "META-INF/bosca/bml/build-id"
