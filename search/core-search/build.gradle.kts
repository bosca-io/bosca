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
    implementation(project(":sharedqueue:sharedqueue"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

ksp {
    arg("ProviderRegistrarPrefix", "CoreSearch")
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {

}

kover {
    reports {
        // Scoped to the search-document pipeline contract + parsing (the rest of core-search is plain
        // contracts/models). Achieved line 100%, branch 100% — held at the standard.
        filters {
            includes { classes("bosca.search.pipeline.*") }
            excludes { annotatedBy("bosca.di.annotation.Generated") }
        }
        verify {
            rule("Search document pipeline contract — line coverage") {
                minBound(100)
            }
            rule("Search document pipeline contract — branch coverage") {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 98
                }
            }
        }
    }
}