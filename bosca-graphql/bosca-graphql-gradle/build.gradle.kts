plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

val publishVersion: String = System.getenv("PUBLISH_VERSION")?.removePrefix("v") ?: "0.0.1"

group = "io.bosca"
version = publishVersion

// Gradle plugin project: a thin plugin that wires the Bosca GraphQL client codegen
// into a consumer's build. It only needs the Gradle/Kotlin plugin APIs — the actual generator
// (io.bosca:bosca-graphql-client) is resolved by Maven coordinate into a worker classpath at the consumer's
// build time, never compiled against here (so kotlinx-serialization stays off Gradle's classpath).
dependencies {
    compileOnly(kotlin("gradle-plugin-api"))
    implementation(kotlin("gradle-plugin"))
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit"))
}

gradlePlugin {
    plugins {
        create("boscaGraphql") {
            id = "io.bosca.graphql"
            implementationClass = "bosca.graphql.gradle.BoscaGraphqlPlugin"
        }
    }
}


tasks.register("cleanProjects") {
    description = "Run clean in all projects"
    dependsOn(
        subprojects.mapNotNull { runCatching { it.tasks.named("clean") }.getOrNull() }
    )
}
