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
    implementation(project(":git:core-git-ci"))
    implementation(project(":git:git"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":bosca-core:core-scheduler"))
    implementation(project(":kubernetes:core-kubernetes"))
    // @JobDefinition KSP emits a pipeline-aware enqueue(context, nodeId) → needs core-pipelines
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":sharedqueue:sharedqueue"))

    implementation(libs.caffeine)
    implementation(libs.snakeyaml)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(project(":pipelines:pipelines"))
    testImplementation(project(":artifacts:core-artifacts"))
    testImplementation(project(":artifacts:artifacts-base"))
    testImplementation(project(":workops:workops"))
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.flyway.postgresql)
}

ksp {
    arg("ProviderRegistrarPrefix", "GitCi")
}

tasks.test {
    // WorkspacePipelinesTest parses the release definitions directly from the workspace.
    inputs.files(rootProject.fileTree(".bosca/pipelines") { include("*.yaml") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
