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
    implementation(project(":bosca-core:core-forms"))
    implementation(project(":content:core-content"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":social:core-profile"))
    implementation(project(":social:profile"))
    implementation(project(":sharedqueue:sharedqueue"))
    // @JobDefinition KSP emits a pipeline-aware enqueue(context, nodeId) → needs core-pipelines
    implementation(project(":pipelines:core-pipelines"))


    implementation(libs.kotlinx.datetime)
    implementation(libs.caffeine)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
}

ksp {
    arg("ProviderRegistrarPrefix", "Forms")
}
