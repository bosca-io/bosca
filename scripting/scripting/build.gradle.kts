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
    implementation(project(":git:core-git"))
    implementation(project(":scripting:core-scripting"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":ai:core-ai"))
    implementation(project(":content:core-content"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":communications:core-communications"))
    implementation(project(":bosca-core:core-events"))
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":content:content"))
    implementation(project(":integrations:hubspot"))

    implementation(project(":sharedqueue:sharedqueue"))

    implementation(libs.caffeine)

    // Outbound transport for SendWebhook / SendSlack trigger-binding actions.
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
}

ksp {
    arg("ProviderRegistrarPrefix", "Scripting")
}
