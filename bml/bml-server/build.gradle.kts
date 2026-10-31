plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kover)
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

// the dedicated SSR server composition root. Lean — bosca-core's
// Netty engine and the BML render runtime only; no domain registrars.
// Data is fetched via the Bosca GraphQL API at a configurable endpoint.
dependencies {
    implementation(project(":bosca-core:core"))
    implementation(project(":bml:core-bml"))
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
}

application {
    mainClass.set("bosca.bml.server.MainKt")
}

tasks.test {
    filter { excludeTestsMatching("bosca.bml.server.BmlChunkCacheBrowserTest") }
}

tasks.register<Test>("chunkCacheBrowserTest") {
    group = "verification"
    description = "Verify content-addressed module caching across deployments in a real browser."
    dependsOn(tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("bosca.bml.server.BmlChunkCacheBrowserTest") }
}

apply(from = rootProject.file("gradle/bml-test-browser.gradle.kts"))
