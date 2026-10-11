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

dependencies {
    api(project(":content:core-bible-providers"))
    implementation(project(":bosca-core:core-configuration"))
    implementation(project(":sharedqueue:sharedqueue"))
    // @JobDefinition KSP emits pipeline-aware enqueue helpers.
    implementation(project(":pipelines:core-pipelines"))
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(project(":bosca-core:configuration"))
    testImplementation(project(":bosca-core:core-security"))
    testImplementation(project(":bosca-core:security"))
    testImplementation(project(":bosca-core:core-storage"))
    testImplementation(project(":content:content"))
    testImplementation(project(":content:slugs"))
    testImplementation(libs.mockk)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:di-ksp"))
    "ksp"(project(":services-di:service-ksp"))
}

ksp {
    arg("ProviderRegistrarPrefix", "BibleProviders")
}

tasks.withType<com.google.devtools.ksp.gradle.KspAATask>().configureEach {
    if (name.contains("Test")) {
        commandLineArgumentProviders.add(
            org.gradle.process.CommandLineArgumentProvider { listOf("isTest=true") }
        )
    }
}

kover {
    reports {
        filters {
            excludes { annotatedBy("bosca.di.annotation.Generated") }
        }
    }
}
