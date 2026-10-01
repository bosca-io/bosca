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

tasks.register("updateBoscaVersions") {
    description = "Update the shared Bosca catalog version and npm manifests"
    doLast {
        // Strip a leading "v": publishes strip it too (PUBLISH_VERSION.removePrefix("v")), so a
        // v-prefixed catalog entry can never resolve against the registry. The unresolvable
        // "v6.0.0" literals baked into the tag-6.0.2 POMs were exactly this failure.
        val version = (System.getenv("RELEASE_VERSION")
            ?: throw GradleException("RELEASE_VERSION environment variable is required")).removePrefix("v")
        val catalog = rootDir.resolve("gradle/libs.versions.toml")
        val original = catalog.readText()
        val versionPattern = Regex("""(?m)^(bosca\s*=\s*")[^"]+(")$""")
        check(versionPattern.containsMatchIn(original)) { "Missing bosca version in gradle/libs.versions.toml" }
        val updated = versionPattern.replace(original) { match ->
            "${match.groupValues[1]}$version${match.groupValues[2]}"
        }
        if (updated != original) {
            catalog.writeText(updated)
            println("Updated: gradle/libs.versions.toml")
        }

        // npm-managed apps (package.json + package-lock.json at the module root; the pnpm
        // monorepos version their own packages) pin @bosca/* client packages from the Bosca
        // npm registry. Those packages ship from different release streams (web packages
        // release with the workspace tag, @bosca/bml from the bml repo), so the registry —
        // not RELEASE_VERSION — is the source of truth: `npm update --save` re-resolves each
        // dependency to its latest published in-range version and rewrites both manifests.
        // Auth comes from each app's committed .npmrc. Retries cover the release-web publish
        // job still running when this task fires (separate pipeline, no cross-pipeline needs).
        val moduleDirs = rootDir.listFiles().orEmpty().toList() + rootDir.resolve("apps").listFiles().orEmpty().toList()
        moduleDirs.filter { it.isDirectory && it.resolve("package-lock.json").exists() }.forEach { dir ->
            val packageJson = dir.resolve("package.json")
            if (!packageJson.exists()) return@forEach
            val parsed = groovy.json.JsonSlurper().parse(packageJson)
            val dependencies = (parsed as? Map<*, *>)?.get("dependencies") as? Map<*, *> ?: return@forEach
            val boscaPackages = dependencies.keys.filterIsInstance<String>().filter { it.startsWith("@bosca/") }
            if (boscaPackages.isEmpty()) return@forEach
            val command = listOf("npm", "update", "--save", "--package-lock-only", "--no-audit", "--no-fund") + boscaPackages
            val maxAttempts = 5
            for (attempt in 1..maxAttempts) {
                val process = ProcessBuilder(command).directory(dir).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() == 0) {
                    println("Updated: ${dir.name}/package.json (${boscaPackages.joinToString(", ")})")
                    break
                }
                if (attempt == maxAttempts) {
                    throw GradleException("npm update failed in ${dir.name} after $maxAttempts attempts:\n$output")
                }
                println("npm update failed in ${dir.name} (attempt $attempt/$maxAttempts), retrying in 30s...\n$output")
                Thread.sleep(30_000)
            }
        }
    }
}
