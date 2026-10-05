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
    implementation(project(":ai:ai"))
    implementation(project(":ai:core-ai"))
    implementation(project(":analytics:core-analytics"))
    implementation(project(":analytics:analytics"))

    implementation(libs.kotlinx.datetime)

    implementation(libs.koog.agents)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.koog.agents.test)
}

ksp {
    arg("ProviderRegistrarPrefix", "AnalyticsAI")
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