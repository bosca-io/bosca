@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/**
 * bosca-graphql — a hand-written, dependency-free GraphQL language toolkit (lexer + AST + parser),
 * the foundation for a custom Bosca GraphQL system that will eventually replace graphql-java. It is
 * Kotlin Multiplatform (pure stdlib, no platform APIs) so it runs everywhere Bosca does: the JVM
 * server, the GraalVM-native CLI, and web/WASM clients.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kover)
}

kotlin {
    jvm()

    js {
        nodejs()
        browser {
            testTask { enabled = false } // browser tests need a headless Chrome; node covers JS
        }
    }

    wasmJs {
        nodejs()
        browser {
            testTask { enabled = false }
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kover {
    reports {
        // No exclusions: this module is 100% hand-written (no KSP / no @Generated code), so the whole of it
        // is held to the bar. Achieved: line 100%, branch 100%, instruction 100% — every reachable branch is
        // tested and every provably-dead branch was deleted/restructured rather than suppressed.
        verify {
            rule("Line coverage") { minBound(100) }
            rule("Branch coverage") {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 100
                }
            }
        }
    }
}
