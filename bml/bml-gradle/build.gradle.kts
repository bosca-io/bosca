plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

val publishVersion: String = System.getenv("PUBLISH_VERSION")?.removePrefix("v") ?: "0.0.1"

group = "io.bosca"
version = publishVersion

// Gradle plugin project: a KotlinCompilerPluginSupportPlugin that wires
// the BML K2 compiler plugin into Kotlin compilation. It only needs the Gradle/Kotlin plugin APIs —
// it references io.bosca:bml-compiler by coordinates (getPluginArtifact), not as a compile dependency.
dependencies {
    compileOnly(kotlin("gradle-plugin-api"))
    implementation(kotlin("gradle-plugin"))
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit"))
}

gradlePlugin {
    plugins {
        create("bml") {
            id = "io.bosca.bml"
            implementationClass = "bosca.bml.gradle.BmlGradlePlugin"
        }
    }
}

tasks.processResources {
    val pluginResourceVersion = publishVersion
    inputs.property("bmlPluginVersion", pluginResourceVersion)
    filesMatching("bosca/bml/gradle/bml-gradle-plugin.properties") {
        expand("pluginVersion" to pluginResourceVersion)
    }
}


tasks.register("cleanProjects") {
    description = "Run clean in all projects"
    dependsOn(
        subprojects.mapNotNull { runCatching { it.tasks.named("clean") }.getOrNull() }
    )
}
