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
    implementation(project(":bosca-core:core-security"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":artifacts:artifacts-base"))
    implementation(project(":bosca-core:security"))
    implementation(libs.bcprov.jdk18on)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(project(":bosca-core:storage"))
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(libs.testcontainers)
    testImplementation(platform(libs.aws.bom))
    testImplementation("software.amazon.awssdk:s3")
    testImplementation(libs.logback.classic)
    testRuntimeOnly(libs.postgresql)
}

ksp {
    arg("ProviderRegistrarPrefix", "ArtifactsDocker")
}
