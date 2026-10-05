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
    implementation(project(":social:core-profile"))
    implementation(project(":social:core-community"))
    implementation(project(":communications:core-communications"))

    implementation(project(":social:profile"))
    implementation(project(":firebase-scrypt:firebase-scrypt"))
    implementation(project(":sharedqueue:sharedqueue"))
    // @JobDefinition KSP emits pipeline-aware enqueue helpers.
    implementation(project(":pipelines:core-pipelines"))

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    implementation(libs.bcpkix.jdk18on)
    implementation(libs.auth0.jwt)
    implementation(libs.auth0.jwks.rsa)
    implementation(libs.webauthn4j.core)

    implementation(libs.caffeine)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(project(":bosca-core:test-support"))
}

ksp {
    arg("ProviderRegistrarPrefix", "Security")
}

kover {
    reports {
        filters {
            excludes {
                annotatedBy("bosca.di.annotation.Generated")
                // Kotlin serialization emits decoder branches that are not hand-written security behavior.
                classes("*\$serializer", "*${'$'}*\$serializer")
                annotatedBy("kotlinx.serialization.Serializable")
                classes("bosca.routes.SecurityRoutesKt*")
            }
        }
        verify {
            rule("security module — line coverage") {
                minBound(99)
            }
            rule("security module — branch coverage") {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 98
                }
            }
        }
    }
}
