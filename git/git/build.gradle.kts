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
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":social:core-profile"))
    implementation(project(":social:profile"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":content:core-comments"))
    implementation(project(":search:core-search"))
    implementation(project(":sharedqueue:sharedqueue"))

    implementation(libs.jgit)
    implementation(libs.caffeine)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    // The git-CLI end-to-end suite runs the production BoscaAuthMiddleware against real Basic-auth traffic.
    testImplementation(project(":bosca-core:security"))
    testImplementation(project(":bosca-core:storage"))
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(libs.testcontainers.postgresql)
}

ksp {
    arg("ProviderRegistrarPrefix", "Git")
}

kover {
    reports {
        filters {
            // Module-wide coverage gate over ALL hand-written code. Only genuinely GENERATED code is
            // excluded (emitted with @bosca.di.annotation.Generated: repository JDBC impls, DI
            // providers/registrars, job-enqueuer wiring) — never hand-written classes.
            excludes { annotatedBy("bosca.di.annotation.Generated") }
        }
        // Enforce the coverage bar on hand-written code (only KSP-generated code is excluded above).
        verify {
            rule("git module — line coverage") {
                minBound(98)
            }
            rule("git module — branch coverage") {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 85
                }
            }
        }
    }
}
