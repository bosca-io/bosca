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
    // Phase 4 reuses CommentStatus from core-comments and ProfileVisibility
    // from core-profile so the workops task_comment shape matches the
    // metadata-comment shape exactly (R6).
    implementation(project(":content:core-comments"))
    implementation(project(":social:core-profile"))

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
}

ksp {
    arg("ProviderRegistrarPrefix", "CoreWorkOps")
}
