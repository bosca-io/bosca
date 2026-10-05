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
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    implementation(project(":bosca-core:core"))
    implementation(project(":content:core-content"))
    implementation(project(":social:core-profile"))
    implementation(project(":search:core-search"))
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":bosca-core:core-configuration"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":social:profile"))
    implementation(project(":content:content"))
    implementation(project(":sharedqueue:sharedqueue"))

    implementation(libs.kotlinx.serialization.json)
    implementation(project(":integrations:meilisearch"))
    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
}

ksp {
    arg("ProviderRegistrarPrefix", "Search")
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {

}

kover {
    reports {
        // A module-wide bound isn't meaningful for `search` (most of it is SearchServiceImpl, exercised
        // by the Meilisearch integration test), and Kover 0.9 has no per-rule class filter — so this
        // project's Kover report is intentionally SCOPED to the search-indexing pipeline nodes, giving
        // `koverVerify` a floor on exactly that code. Does not affect the server's aggregate report.
        filters {
            includes { classes("bosca.search.pipeline.*") }
            excludes { annotatedBy("bosca.di.annotation.Generated") }
        }
        verify {
            // Achieved: line 100%, branch 98.5%. The only residual is the two `input.typeName?.let` arms in
            // the dry-run trace of IndexNode/RemoveFromIndexNode — bytecode-unreachable: typeName is never
            // null for a typed Indexable, and a non-Indexable input errors before the dry-run block. (The
            // marker/contentId JSON parsing was extracted to SearchDocumentPipeline and is tested
            // exhaustively in core-search, so it no longer contributes phantom arms here.)
            rule("Search index pipeline nodes — line coverage") {
                minBound(100)
            }
            rule("Search index pipeline nodes — branch coverage") {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 98
                }
            }
        }
    }
}
