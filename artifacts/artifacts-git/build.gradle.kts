plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    implementation(project(":bosca-core:core"))
    implementation(project(":artifacts:core-artifacts"))
    implementation(project(":git:core-git-ci"))
    ksp(project(":bosca-core:core-ksp"))
    ksp(project(":services-di:service-ksp"))
    ksp(project(":services-di:di-ksp"))

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(project(":git:git-ci"))
    testImplementation(project(":git:core-git"))
    testImplementation(project(":artifacts:artifacts-base"))
    testImplementation(project(":bosca-core:core-security"))
    testImplementation(project(":bosca-core:core-storage"))
    testImplementation(project(":kubernetes:core-kubernetes"))
    testImplementation(project(":pipelines:core-pipelines"))
    testImplementation(project(":pipelines:pipelines"))
    testImplementation(project(":sharedqueue:sharedqueue"))
    testImplementation(project(":bosca-core:test-support"))
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.flyway.postgresql)
}

ksp {
    arg("ProviderRegistrarPrefix", "ArtifactsGit")
}

tasks.withType<com.google.devtools.ksp.gradle.KspAATask>().configureEach {
    if (name.contains("Test")) {
        commandLineArgumentProviders.add(
            org.gradle.process.CommandLineArgumentProvider { listOf("isTest=true") }
        )
    }
}
