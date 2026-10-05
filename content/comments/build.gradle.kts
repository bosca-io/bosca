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
    implementation(project(":bosca-core:core-security"))
    // core-content is a sibling in the content build now (comments live alongside metadata)
    implementation(project(":content:core-content"))
    implementation(project(":bosca-core:core-configuration"))
    // @JobDefinition KSP emits a pipeline-aware enqueue(context, nodeId) → needs core-pipelines
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":sharedqueue:sharedqueue"))
    // Koog moderation: omni-moderation via Koog's OpenAI client, replacing
    // the OpenAI Java SDK (native-image friendly — Ktor + kotlinx.serialization).
    implementation(libs.koog.agents)
    implementation(libs.koog.prompt.model)
    implementation(libs.koog.prompt.llm)
    implementation(project(":content:core-comments"))
    // profile lives in the social build — consume it via its published coordinate
    implementation(project(":social:core-profile"))
    implementation(project(":social:profile"))

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(libs.testcontainers.postgresql)
}

ksp {
    arg("ProviderRegistrarPrefix", "Comments")
}
