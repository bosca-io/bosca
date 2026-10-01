import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
    implementation(project(":bosca-core:core-events"))
    implementation(project(":analytics:analytics-models"))
    implementation(project(":analytics:analytics-server-client"))
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":social:core-profile"))
    implementation(project(":social:core-community"))
    implementation(project(":content:core-content"))
    implementation(project(":bosca-core:core-forms"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":search:core-search"))
    implementation(project(":communications:core-communications"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":sharedqueue:sharedqueue"))
    implementation(project(":integrations:hubspot"))

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(project(":content:slugs"))
    testImplementation(libs.testcontainers.postgresql)
}

ksp {
    arg("ProviderRegistrarPrefix", "Profile")
}
