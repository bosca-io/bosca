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
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    implementation(project(":bosca-core:core"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":git:core-git-ci"))
    implementation(project(":workops:core-workops"))

    // real Google Play Developer API (Android Publisher) for the concrete PlayPublisher.
    implementation(libs.google.androidpublisher)
    implementation(libs.google.auth.oauth2)

    // App Store Connect API is REST + a JWT (ES256); okhttp for transport,
    // auth0 java-jwt (transitive via bosca-core) for the token.
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(project(":pipelines:pipelines"))
}

ksp {
    arg("ProviderRegistrarPrefix", "StorePipelines")
}

kover {
    reports {
        filters {
            excludes {
                // KSP-generated DI, serializer, and pipeline codec classes carry this annotation.
                // Keep every hand-written store implementation and node in the report.
                annotatedBy("bosca.di.annotation.Generated")
            }
        }
        verify {
            rule {
                minBound(99)
            }
            rule {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 97
                }
            }
        }
    }
}
