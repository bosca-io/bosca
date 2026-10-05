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
    implementation(project(":social:core-chat"))
    implementation(project(":social:chat"))
    implementation(project(":social:core-community"))
    implementation(project(":social:core-profile"))
    implementation(project(":content:core-content"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":search:core-search"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":communications:core-communications"))
    implementation(project(":communications:communications"))
    implementation(project(":social:profile"))
    // @JobDefinition KSP emits a pipeline-aware enqueue(context, nodeId) → needs core-pipelines
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":sharedqueue:sharedqueue"))
    implementation(project(":bosca-core:core-scheduler"))

    implementation(libs.koog.agents)


    implementation(libs.kotlinx.datetime)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(libs.testcontainers.postgresql)
}

ksp {
    arg("ProviderRegistrarPrefix", "Community")
}
