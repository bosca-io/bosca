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

dependencies {
    api(project(":analytics:analytics-models"))
    implementation(project(":bosca-core:core"))
    implementation(project(":analytics:core-analytics"))
    implementation(project(":git:core-git"))
    implementation(project(":sharedqueue:sharedqueue"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":scripting:core-scripting"))
    implementation(project(":bosca-core:core-events"))

    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.protobuf)

    implementation(libs.iceberg.core)
    implementation(libs.iceberg.data)
    implementation(libs.iceberg.parquet)
    implementation(libs.iceberg.aws)
    implementation(libs.parquet.column)
    implementation(libs.hadoop.common)
    implementation(libs.hadoop.mapreduce.client.core)

    implementation(libs.trino)

    implementation(platform(libs.aws.bom))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:sso")
    implementation("software.amazon.awssdk:ssooidc")
    implementation("software.amazon.awssdk:kms")
    implementation("software.amazon.awssdk:sts")

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(project(":ai:kit"))
    testImplementation(project(":ai:core-ai"))
    testImplementation(libs.koog.agents)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
        
    }
}

ksp {
    arg("ProviderRegistrarPrefix", "Analytics")
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
