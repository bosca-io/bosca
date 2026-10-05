plugins {
    id("org.jetbrains.kotlin.multiplatform") apply false
    id("com.android.kotlin.multiplatform.library") apply false
    alias(libs.plugins.kover) apply false
}

val publishVersion: String = System.getenv("PUBLISH_VERSION")?.removePrefix("v") ?: "0.0.1"

allprojects {
    group = "io.bosca"
    version = publishVersion

    apply(plugin = "maven-publish")

    // Publish resolved dependency versions so local project dependencies carry
    // the same workspace version as the artifacts built from them. bosca-yks is
    // MIT-licensed (see bosca-yks/LICENSE), unlike the rest of the workspace.
    extensions.configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            versionMapping { allVariants { fromResolutionResult() } }
            pom {
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }

    project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
        project.afterEvaluate {
            val artifactId = path.removePrefix(":bosca-yks:").replace(':', '-')
            extensions.configure<PublishingExtension> {
                publications.configureEach {
                    if (this is MavenPublication) {
                        val projectBaseName = project.name
                        this.artifactId = when {
                            this.artifactId.startsWith("$projectBaseName-") ->
                                artifactId + this.artifactId.removePrefix(projectBaseName)
                            this.artifactId == projectBaseName -> artifactId
                            else -> this.artifactId
                        }
                    }
                }
            }
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
            
        }
    }
}


tasks.register("cleanProjects") {
    description = "Run clean in all projects"
    dependsOn(
        subprojects.mapNotNull { runCatching { it.tasks.named("clean") }.getOrNull() }
    )
}

tasks.register("test") {
    description = "Run tests in all projects"
    dependsOn(
        subprojects.mapNotNull { runCatching { it.tasks.named("test") }.getOrNull() }
    )
}
