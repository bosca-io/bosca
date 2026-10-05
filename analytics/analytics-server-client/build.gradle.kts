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
    api(project(":analytics:analytics-models"))
    api(project(":analytics:core-analytics"))
    implementation(project(":bosca-core:core"))

    // HttpServerAnalyticsClient uses OkHttp directly. core declares okhttp
    // as `implementation`, so it's not visible transitively — we need our
    // own declaration for both compile and test sources.
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
    arg("ProviderRegistrarPrefix", "AnalyticsServerClient")
}

kover {
    reports {
        filters {
            excludes {
                annotatedBy("bosca.di.annotation.Generated")
            }
        }
        verify {

            rule("Line coverage") { minBound(99) }
            rule("Branch coverage") {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 98
                }
            }
        }
    }
}