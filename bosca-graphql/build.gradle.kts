plugins {
    // kotlin.jvm is pinned here (apply false) even though only :bosca-graphql-gradle applies it: applying
    // kotlin.multiplatform in the sibling modules drops the kotlin-gradle-plugin JAR onto the build's shared
    // plugin classpath, which also registers the org.jetbrains.kotlin.jvm id — but with an *unknown* version.
    // Without this line, :bosca-graphql-gradle's `kotlin("jvm") version "2.4.0"` can't be verified against that
    // unknown classpath version and fails ("already on the classpath with an unknown version"). Pinning it to the
    // known catalog version makes the versioned request match. (bml/build.gradle.kts does the same for bml-gradle.)
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.multiplatform") apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.kotlinx.benchmark) apply false
}

val publishVersion: String = System.getenv("PUBLISH_VERSION")?.removePrefix("v") ?: "0.0.1"

allprojects {
    if (path == ":bosca-graphql:bosca-graphql-gradle") return@allprojects

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
            val artifactId = path.removePrefix(":bosca-graphql:").replace(':', '-')
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
