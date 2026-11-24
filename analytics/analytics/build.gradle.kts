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
    implementation(project(":content:core-bible-providers"))
    implementation(project(":git:core-git"))
    implementation(project(":sharedqueue:sharedqueue"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":scripting:core-scripting"))
    implementation(project(":bosca-core:core-events"))

    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

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
    testImplementation(libs.logback.classic)
    testImplementation(libs.okhttp.mockwebserver)
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

// Run independently of tests so coverage instrumentation does not distort disk timings.
val benchmark = sourceSets.create("benchmark") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[benchmark.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[benchmark.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

kover {
    currentProject {
        sources {
            // Measurement harnesses follow the core module's non-production source-set policy.
            excludedSourceSets.add(benchmark.name)
        }
    }
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




// This executable verifies queue code and serializers; native collector wiring is checked separately.
val nativeQueueClasspath = benchmark.runtimeClasspath.filter { file ->
    !file.name.endsWith(".jar") || listOf(
        "kotlin-stdlib-", "kotlinx-coroutines-core-jvm-", "kotlinx-serialization-json-jvm-",
        "kotlinx-serialization-core-jvm-", "slf4j-api-", "core-analytics-", "analytics-models-",
    ).any { file.name.startsWith(it) }
}
val nativeQueueExecutable = layout.buildDirectory.file("native/diskQueueSmoke/analytics-disk-queue-smoke")
val compileNativeDiskAnalyticsQueueSmoke = tasks.register<Exec>("compileNativeDiskAnalyticsQueueSmoke") {
    group = "verification"
    description = "Builds the disk queue smoke executable with GraalVM native-image on PATH."
    dependsOn(benchmark.classesTaskName)
    inputs.files(nativeQueueClasspath).withPropertyName("nativeQueueClasspath").withNormalizer(ClasspathNormalizer::class)
    outputs.file(nativeQueueExecutable)
    commandLine("native-image", "--no-fallback", "-O2", "-J-Xmx6g", "-cp", nativeQueueClasspath.asPath,
        "bosca.analytics.queue.DiskAnalyticsNativeSmoke", nativeQueueExecutable.get().asFile.absolutePath)
    val outputDirectory = nativeQueueExecutable.get().asFile.parentFile
    doFirst { outputDirectory.mkdirs() }
}
tasks.register<Exec>("nativeDiskAnalyticsQueueSmoke") {
    group = "verification"
    description = "Checks native enqueue, independent retries and acknowledgment recovery across processes."
    dependsOn(compileNativeDiskAnalyticsQueueSmoke)
    commandLine(nativeQueueExecutable.get().asFile.absolutePath, "verify")
    outputs.upToDateWhen { false }
}

// Read-failure injection spies on RandomAccessFile without changing the production I/O path.
tasks.test {
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
}
