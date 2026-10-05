package bosca.analytics.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.util.Properties

/** Wires Bosca's compiler instrumentation and portable runtime into Kotlin JVM/KMP compilations. */
class AnalyticsGradlePlugin : KotlinCompilerPluginSupportPlugin {
    private lateinit var extension: AnalyticsExtension

    override fun apply(target: Project) {
        extension = target.extensions.create("analytics", AnalyticsExtension::class.java)
        val runtime = "io.bosca:analytics-core:$PLUGIN_VERSION"

        target.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            val kotlin = target.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.sourceSets.getByName("commonMain").dependencies {
                implementation(runtime)
            }
        }
        target.plugins.withId("org.jetbrains.kotlin.jvm") {
            target.dependencies.add("implementation", runtime)
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = extension.enabled.get()

    override fun getCompilerPluginId(): String = "bosca.analytics"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "io.bosca",
        artifactId = "analytics-compiler",
        version = PLUGIN_VERSION,
    )

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> =
        kotlinCompilation.target.project.provider {
            listOf(
                SubpluginOption("enabled", extension.enabled.get().toString()),
                SubpluginOption("verbose", extension.verbose.get().toString()),
            )
        }

    private companion object {
        val PLUGIN_VERSION: String = Properties().run {
            AnalyticsGradlePlugin::class.java.getResourceAsStream("analytics-gradle-plugin.properties")?.use(::load)
            getProperty("version") ?: "0.0.1"
        }
    }
}
