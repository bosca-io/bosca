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
    api(project(":social:core-collaboration"))
    implementation(project(":bosca-core:core"))
    implementation(project(":bosca-core:core-events"))
    implementation(project(":social:core-chat"))
    implementation(project(":social:chat"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":social:core-profile"))
    implementation(project(":social:profile"))
    implementation(project(":communications:core-communications"))
    implementation(project(":bosca-core:core-configuration"))
    implementation(project(":ai:core-ai"))
    implementation(project(":ai:kit"))
    implementation(libs.kotlinx.coroutines.rx3)
    // @JobDefinition KSP emits a pipeline-aware enqueue(context, nodeId) → needs core-pipelines
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":sharedqueue:sharedqueue"))

    implementation(libs.kotlinx.datetime)
    implementation(libs.nats) { exclude(group = "org.bouncycastle", module = "bcprov-lts8on") }
    implementation(libs.ktor.client.java)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.ktor.client.mock)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    maxHeapSize = "1g"
}

ksp {
    arg("ProviderRegistrarPrefix", "Collaboration")
}
