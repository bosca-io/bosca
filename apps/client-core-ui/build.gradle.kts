plugins {
    id("org.jetbrains.kotlin.multiplatform") apply false
    id("org.jetbrains.kotlin.native.cocoapods") apply false
    id("com.android.kotlin.multiplatform.library") apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.kover) apply false
    id("io.bosca.graphql") apply false
}

val publishVersion: String = System.getenv("PUBLISH_VERSION")?.removePrefix("v") ?: "0.0.1"

allprojects {
    group = "io.bosca"
    version = publishVersion

    apply(plugin = "maven-publish")

    // Publish resolved dependency versions so local project dependencies carry
    // the same workspace version as the artifacts built from them.
    extensions.configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            versionMapping { allVariants { fromResolutionResult() } }
        }
    }

    // KMP modules register their own multiplatform publications; fix the artifactId so they publish as
    // `io.bosca:<path>` (and the per-target `…-jvm` / `…-iosarm64` variants). Mirrors bosca-core / services-di.
    project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
        project.afterEvaluate {
            val artifactId = path.removePrefix(":client-core-ui:").replace(':', '-')
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

    tasks.withType<Test> {
        maxHeapSize = "2g"
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED")
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        jvmArgs("-XX:+IgnoreUnrecognizedVMOptions")
    }
}

// Component-level aggregate tasks remain available under this project path.
// Mirrors bosca-core; the test task tolerates KMP target naming (jvm() -> jvmTest, jvm("desktop") -> desktopTest).
tasks.register("test") {
    description = "Run JVM-side tests in all subprojects"
    dependsOn(
        subprojects.mapNotNull { sp ->
            listOf("jvmTest", "desktopTest", "test").firstNotNullOfOrNull { name ->
                runCatching { sp.tasks.named(name) }.getOrNull()
            }
        },
    )
}

tasks.register("cleanProjects") {
    description = "Run clean in all subprojects"
    dependsOn(subprojects.mapNotNull { runCatching { it.tasks.named("clean") }.getOrNull() })
}
