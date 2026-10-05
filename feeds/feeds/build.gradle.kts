plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
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

dependencies {
    implementation(project(":bosca-core:core"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":feeds:core-feeds"))

    // Cross-module contracts only (no impl -> impl)
    implementation(project(":content:core-content"))
    implementation(project(":bosca-core:core-configuration"))
    implementation(project(":social:core-profile"))
    implementation(project(":pipelines:core-pipelines"))
    // Feed-serving delegates ranking to the recommendations domain; contracts only.
    implementation(project(":experimentation:core-recommendations"))

    // Feed envelope parsing (RSS/ATOM XML + JSON Feed)
    implementation(libs.ksoup)

    // Outbound HTTP for fetching feeds
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    // Domain events & background jobs
    implementation(project(":bosca-core:core-events"))
    implementation(project(":sharedqueue:sharedqueue"))
    implementation(project(":bosca-core:core-scheduler"))

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(libs.testcontainers.postgresql)
}

ksp {
    arg("ProviderRegistrarPrefix", "Feeds")
}
