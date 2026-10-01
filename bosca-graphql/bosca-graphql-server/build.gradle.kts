/**
 * bosca-graphql-server — the Bosca-native GraphQL execution engine (the graphql-java replacement), built on
 * the `bosca-graphql` foundation. Holds the executable-schema/wiring layer and (incrementally) coercion, the
 * coroutine-based execution engine, introspection, DataLoader batching, and Flow-based subscriptions.
 *
 * Kotlin Multiplatform with the engine in `commonMain` (coroutines are multiplatform); the JVM target is the
 * one wired into bosca-core for now.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kover)
    alias(libs.plugins.kotlinx.benchmark)
}

kotlin {
    jvm {
        compilations.create("benchmark") {
            associateWith(this@jvm.compilations.getByName("main"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":bosca-graphql:bosca-graphql"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json) // JsonElement is the wire model for coercion/results
            implementation(libs.kotlinx.datetime) // the DateTime scalar
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        named("jvmBenchmark") {
            dependencies {
                implementation(libs.kotlinx.benchmark.runtime)
            }
        }
    }
}

benchmark {
    targets {
        register("jvmBenchmark")
    }
    configurations {
        named("main") {
            exclude(".*LoadBenchmark.*")
            warmups = 5
            iterations = 10
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "us"
            mode = "avgt"
            reportFormat = "json"
            advanced("jvmForks", 2)
        }
        register("smoke") {
            exclude(".*LoadBenchmark.*")
            warmups = 1
            iterations = 2
            iterationTime = 250
            iterationTimeUnit = "ms"
            outputTimeUnit = "us"
            mode = "avgt"
            reportFormat = "json"
            advanced("jvmForks", 1)
        }
        register("load") {
            include(".*LoadBenchmark.*")
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "s"
            mode = "thrpt"
            reportFormat = "json"
            advanced("jvmForks", 2)
        }
        register("parser") {
            include(".*GraphQLParserBenchmark.*")
            warmups = 5
            iterations = 10
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "us"
            mode = "avgt"
            reportFormat = "json"
            advanced("jvmForks", 2)
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
        // Benchmarks are a separate measurement harness, not production code exercised by unit tests. Production
        // engine code remains unexcluded and must retain 100% line / 98% branch coverage.
        filters {
            excludes {
                classes("bosca.graphql.server.*Benchmark*")
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
