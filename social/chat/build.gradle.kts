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
    api(project(":social:core-chat"))
    implementation(project(":bosca-core:core"))
    implementation(project(":bosca-core:core-events"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":bosca-core:security"))
    implementation(project(":social:core-profile"))
    implementation(project(":social:profile"))
    implementation(project(":communications:core-communications"))
    implementation(project(":content:core-content"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":sharedqueue:sharedqueue"))
    // @JobDefinition generates a pipeline-aware enqueue overload.
    implementation(project(":pipelines:core-pipelines"))

    implementation(libs.kotlinx.datetime)
    implementation(libs.nats) { exclude(group = "org.bouncycastle", module = "bcprov-lts8on") }

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(project(":social:core-community"))

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

ksp {
    arg("ProviderRegistrarPrefix", "Chat")
}
