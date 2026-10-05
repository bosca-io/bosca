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
    implementation(project(":ai:core-ai"))
    implementation(project(":search:core-search"))
    implementation(project(":content:core-content"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":bosca-core:core-configuration"))
    implementation(project(":git:core-git"))
    implementation(project(":scripting:core-scripting"))
    implementation(project(":bosca-kmp:bible-dom-shared"))
    implementation(libs.kotlinx.datetime)

    implementation(libs.koog.agents)
    implementation(libs.koog.agents.additions)

    implementation(libs.kotlinx.coroutines.rx3)
    implementation(libs.kotlinx.coroutines.reactive)

    implementation(libs.snakeyaml)
    implementation(libs.kotlinx.serialization.json)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
}

ksp {
    arg("ProviderRegistrarPrefix", "AI")
}