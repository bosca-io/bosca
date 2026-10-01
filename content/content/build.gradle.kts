import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
        
    }
}

dependencies {
    implementation(project(":bosca-core:core"))
    implementation(project(":bosca-core:core-events"))
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":content:core-content"))
    implementation(project(":content:core-comments"))
    implementation(project(":calendar:core-calendar"))
    implementation(project(":bosca-core:core-forms"))
    implementation(project(":bosca-core:core-configuration"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":social:core-profile"))
    implementation(project(":search:core-search"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":ai:core-ai"))
    implementation(project(":social:profile"))
    implementation(project(":ai:ai"))
    implementation(project(":bosca-kmp:bible-dom-shared"))
    implementation(project(":content:bible-compiler"))
    implementation(project(":bosca-yks:yks"))

    implementation(libs.google.genai)
    implementation(libs.google.cloud.texttospeech)
    implementation(libs.google.cloud.storage)

    implementation(libs.kotlinx.datetime)
    implementation(libs.jsonata)
    implementation(libs.pdfbox)
    implementation(libs.ksoup)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

//    implementation(project(":content:backend:utilities:collaboration"))
    implementation(project(":sharedqueue:sharedqueue"))

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.okhttp.mockwebserver)
}

ksp {
    arg("ProviderRegistrarPrefix", "Content")
}

kover {
    reports {
        filters {
            excludes { annotatedBy("bosca.di.annotation.Generated") }
        }
        verify {
            rule {
                minBound(91)
            }
            rule {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 73
                }
            }
        }
    }
}
