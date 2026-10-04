plugins {
    base
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.multiplatform") apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    id("com.android.kotlin.multiplatform.library") apply false
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.native.cocoapods") apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.graalvm) apply false
    id("org.jetbrains.intellij.platform") apply false
}

// Compiler plugins request Maven coordinates through Kotlin's plugin API.
// Resolve those requests to source projects; build scripts use project dependencies directly.
val localModules = subprojects
    .filter { it.parent != rootProject }
    .groupBy { it.name }
    .also { modules ->
        val duplicates = modules.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) { "Duplicate module names: ${duplicates.joinToString()}" }
    }
    .mapValues { (_, projects) -> projects.single().path }

allprojects {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            localModules.forEach { (moduleName, projectPath) ->
                substitute(module("io.bosca:$moduleName")).using(project(projectPath))
            }
        }
    }
    plugins.withId("io.bosca.bml") {
        extensions.configure<bosca.bml.gradle.BmlExtension> {
            clientBundler.convention(rootProject.layout.projectDirectory.file("bml/bml-runtime/tools/bundle.mjs"))
            clientRuntime.convention(rootProject.layout.projectDirectory.file("bml/bml-runtime/src/index.ts"))
        }
    }
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            val registryUrl = providers.gradleProperty("boscaRegistryUrl").orNull
            if (registryUrl != null) {
                repositories.maven {
                    name = "bosca"
                    url = uri(registryUrl)
                    credentials {
                        username = providers.gradleProperty("boscaRegistryUsername").orNull
                        password = providers.gradleProperty("boscaRegistryPassword").orNull
                    }
                }
            }
            if (path == ":bml:bml-compiler") {
                repositories.maven {
                    name = "local"
                    url = uri(project(":bml").layout.buildDirectory.dir("repo"))
                }
            }
        }
    }
}

tasks.register("testResourcesUp") {
    group = "verification"
    description = "Start shared PostgreSQL, NATS, Valkey, and Meilisearch integration-test services"
    dependsOn(":bosca-core:test-support:testResourcesUp")
}

tasks.register("testResourcesDown") {
    group = "verification"
    description = "Stop shared PostgreSQL, NATS, Valkey, and Meilisearch integration-test services"
    dependsOn(":bosca-core:test-support:testResourcesDown")
}

tasks.register("publishExceptFirebaseScrypt") {
    group = "publishing"
    description = "Publish workspace libraries and Gradle plugins except Firebase Scrypt"
    dependsOn(provider {
        subprojects
            .filter { it.path != ":firebase-scrypt" && !it.path.startsWith(":firebase-scrypt:") }
            // BmlServer is also an embedded library used by BML applications.
            .filter { !it.plugins.hasPlugin("application") || it.path == ":bml:bml-server" }
            .mapNotNull { it.tasks.findByName("publish") }
    })
}
