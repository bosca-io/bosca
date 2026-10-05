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
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":workops:core-workops"))
    implementation(project(":workops:store-pipelines"))
    implementation(project(":content:core-comments"))
    implementation(project(":bosca-core:core-forms"))
    implementation(project(":social:core-profile"))
    implementation(project(":social:profile"))
    implementation(project(":content:core-content"))
    implementation(project(":content:core-localization"))
    implementation(project(":bosca-yks:yks"))
    implementation(project(":search:core-search"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":bosca-core:core-scheduler"))
    implementation(project(":communications:core-communications"))
    implementation(project(":analytics:core-analytics"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":calendar:core-calendar"))
    implementation(project(":git:core-git"))
    implementation(project(":git:core-git-ci"))
    implementation(project(":sharedqueue:sharedqueue"))
    implementation(project(":artifacts:core-artifacts"))
    implementation(libs.snakeyaml)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.okhttp.mockwebserver)
}

ksp {
    arg("ProviderRegistrarPrefix", "WorkOps")
}

kover {
    reports {
        filters {
            excludes { annotatedBy("bosca.di.annotation.Generated") }
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
