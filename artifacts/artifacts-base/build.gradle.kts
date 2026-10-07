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
    implementation(project(":artifacts:core-artifacts"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":bosca-core:storage"))
    implementation(project(":bosca-core:security"))
    implementation(libs.caffeine)
    implementation(project(":pipelines:core-pipelines"))
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(project(":pipelines:pipelines"))
    testImplementation(project(":sharedqueue:sharedqueue"))
    testImplementation(project(":bosca-core:core-events"))
    testImplementation(project(":bosca-core:core-scheduler"))
}

ksp {
    arg("ProviderRegistrarPrefix", "Artifacts")
}
