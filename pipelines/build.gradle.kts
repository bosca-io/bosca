plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.kover) apply false
}

val publishVersion: String = System.getenv("PUBLISH_VERSION")?.removePrefix("v") ?: "0.0.2"

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

    project.plugins.withId("org.jetbrains.kotlin.jvm") {
        val artifactId = path.removePrefix(":pipelines:").replace(':', '-')
        extensions.configure<PublishingExtension> {
            publications {
                register<MavenPublication>("maven") {
                    this.artifactId = artifactId
                    from(components["java"])
                }
            }
        }

        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension> {
            sourceSets.getByName("main").kotlin.srcDir("build/generated/ksp/main/kotlin")
            sourceSets.getByName("test").kotlin.srcDir("build/generated/ksp/test/kotlin")
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
            
        }
    }

    tasks.withType<Test> {
        // 4g: the pipelines suite (1000+ tests) runs under the Kover coverage agent alongside a
        // Testcontainers Postgres for the durable end-to-end tests; the instrumentation + container
        // overhead exhausts a 2g heap.
        maxHeapSize = "4g"
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED")
        jvmArgs("--enable-native-access=ALL-UNNAMED")
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
