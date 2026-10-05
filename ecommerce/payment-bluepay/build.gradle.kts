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
    implementation(project(":bosca-core:core"))              // bosca.di annotations + ProviderRegistry
    implementation(project(":ecommerce:core-ecommerce"))   // PaymentProcessor SPI + models

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)                  // the platform's standard outbound HTTP client

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver)
}

ksp {
    arg("ProviderRegistrarPrefix", "EcommerceBluePay")
}

kover {
    reports {
        filters {
            excludes {
                annotatedBy("bosca.di.annotation.Generated")
            }
        }
        verify {
            rule("Hand-written line coverage") { minBound(99) }
            rule("Hand-written branch coverage") {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 90
                }
            }
        }
    }
}
