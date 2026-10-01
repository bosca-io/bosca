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
    implementation(project(":experimentation:core-recommendations"))
    implementation(project(":analytics:core-analytics"))
    implementation(project(":artifacts:core-artifacts"))
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":content:core-content"))
    implementation(project(":content:core-languages"))
    implementation(project(":social:core-profile"))
    implementation(project(":bosca-core:core-scheduler"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":kubernetes:core-kubernetes"))
    implementation(project(":experimentation:core-segmentation"))
    implementation(project(":experimentation:core-experimentation"))
    implementation(project(":sharedqueue:sharedqueue"))

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    // JSONata for Personalization Signal expression validation.
    implementation(libs.jsonata)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.trino)
    testImplementation(libs.okhttp.mockwebserver)
}

ksp {
    arg("ProviderRegistrarPrefix", "Recommendations")
}

kover {
    reports {
        filters {
            excludes { annotatedBy("bosca.di.annotation.Generated") }
        }
        verify {
            rule("line coverage") {
                minBound(99)
            }
            rule("branch coverage") {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    // Ceiling for this module without excludes: the remaining branches are Kotlin-generated
                    // and unreachable by test (serializer synthetics, defensive `?:` on never-null values).
                    minValue = 92
                }
            }
        }
    }
}
