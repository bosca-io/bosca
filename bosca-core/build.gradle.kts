plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.kotlinx.benchmark) apply false
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

    // KMP modules (auth-shared) register their own multiplatform publications;
    // we only fix the artifactId so they publish as `io.bosca:<path>` (and the
    // per-target variants `…-jvm`, `…-iosarm64`, …). Mirrors services-di.
    project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
        project.afterEvaluate {
            val artifactId = path.removePrefix(":bosca-core:").replace(':', '-')
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

    project.plugins.withId("org.jetbrains.kotlin.jvm") {
        if (!project.plugins.hasPlugin("application")) {
            val artifactId = path.removePrefix(":bosca-core:").replace(':', '-')
            extensions.configure<PublishingExtension> {
                publications {
                    register<MavenPublication>("maven") {
                        this.artifactId = artifactId
                        from(components["java"])
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

    project.plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension> {
            sourceSets.getByName("main").kotlin.srcDir("build/generated/ksp/main/kotlin")
            sourceSets.getByName("test").kotlin.srcDir("build/generated/ksp/test/kotlin")
        }
    }

    project.plugins.withId("com.google.devtools.ksp") {
        tasks.withType<com.google.devtools.ksp.gradle.KspAATask>().configureEach {
            if (name.contains("Test")) {
                commandLineArgumentProviders.add(
                    org.gradle.process.CommandLineArgumentProvider { listOf("isTest=true") }
                )
            }
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
