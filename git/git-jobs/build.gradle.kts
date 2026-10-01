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
    implementation(project(":bosca-core:core-scheduler"))
    implementation(project(":search:core-search"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":git:git"))
    // @JobDefinition KSP emits a pipeline-aware enqueue(context, nodeId) → needs core-pipelines
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":sharedqueue:sharedqueue"))

    implementation(libs.jgit)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

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
    arg("ProviderRegistrarPrefix", "GitJobs")
}

kover {
    reports {
        filters {
            // Module-wide gate over ALL hand-written git-jobs code. Only KSP-generated code is
            // excluded (emitted with @bosca.di.annotation.Generated: job executors' enqueue wiring,
            // DI providers/registrars).
            excludes { annotatedBy("bosca.di.annotation.Generated") }
        }
        // Enforce near-100% across the whole module, matching the ecommerce/pipelines 99/97 convention.
        verify {
            rule("git-jobs module — line coverage") {
                minBound(99)
            }
            rule("git-jobs module — branch coverage") {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 97
                }
            }
        }
    }
}
