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
    implementation(project(":bosca-core:core-security"))
    implementation(project(":content:core-content"))
    implementation(project(":sharedqueue:sharedqueue"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

ksp {
    arg("ProviderRegistrarPrefix", "CoreCommunications")
}

kover {
    reports {
        // Core communications is otherwise data models and abstract contracts. Hold the
        // executable compatibility bridge on NotificationTypeService to the coverage bar;
        // generated data-class/default-argument bytecode is not behavioral coverage.
        filters {
            includes {
                classes("bosca.communications.service.NotificationTypeService")
            }
            excludes {
                annotatedBy("bosca.di.annotation.Generated")
                classes("bosca.communications.service.NotificationTypeService\$DefaultImpls")
            }
        }
        verify {
            rule("Line coverage") { minBound(100) }
            rule("Branch coverage") {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 98
                }
            }
        }
    }
}
