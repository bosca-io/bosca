import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.kotlinx.benchmark)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

configurations.all {
    // Drop netty-all: a legacy fat aggregator (pulled by Hadoop/Iceberg in :analytics) that
    // drags in stale 4.1.x native modules at versions that don't match the rest of Netty.
    // The epoll transport is instead pulled explicitly at the catalog version (see below).
    exclude(group = "io.netty", module = "netty-all")
}

val jvmBenchmark by sourceSets.creating

dependencies {
    api(project(":bosca-core:core-annotations"))

    // Netty HTTP server (replaces Ktor)
    // Export the release BOM so transitive clients such as Lettuce, AWS, Firebase, and Hadoop
    // cannot leave auxiliary Netty modules on a different patch level.
    api(platform(libs.netty.bom))
    // Explicit common/buffer deps ensure GraalVM native-image finds reachability metadata
    api(libs.netty.common)
    api(libs.netty.buffer)
    api(libs.netty.codec.http)
    api(libs.netty.codec.http2)
    api(libs.netty.handler)
    api(libs.netty.transport)
    // Linux epoll transport for the HTTP server (NettyServerEngine selects it at runtime,
    // falling back to NIO when unavailable). The classes jar is needed at compile time; the
    // native lib is Linux-only and runtime-scoped, so building on macOS still yields a
    // Linux-capable artifact and dev machines transparently run on NIO.
    implementation(libs.netty.transport.classes.epoll)
    runtimeOnly(variantOf(libs.netty.transport.native.epoll) { classifier("linux-x86_64") })
    runtimeOnly(variantOf(libs.netty.transport.native.epoll) { classifier("linux-aarch_64") })
    api(libs.snakeyaml)
    api(libs.auth0.jwt)

    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.protobuf)

    api(project(":services-di:di"))
    api(project(":services-di:service"))

    implementation(libs.caffeine)

    // NKey uses Ed25519 APIs verified against the platform's PKIX provider family.
    // Loading the LTS and jdk18 providers together mixes signed classes in one package.
    api(libs.nats) { exclude(group = "org.bouncycastle", module = "bcprov-lts8on") }
    api(libs.bcprov.jdk18on)

    api(libs.lettuce.core)

    api(project(":bosca-graphql:bosca-graphql-server"))

    api(libs.opentelemetry.kotlin)

    api(libs.opentelemetry.sdk.extension.autoconfigure)
    api(libs.opentelemetry.semconv)
    api(libs.opentelemetry.exporter.otlp)

    implementation(libs.postgresql)

    implementation(libs.jte.kotlin)

    api(libs.kotlinx.coroutines.reactive)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    api(libs.flyway.core)
    api(libs.flyway.postgresql)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))

    "jvmBenchmarkImplementation"(sourceSets.main.get().output)
    "jvmBenchmarkImplementation"(libs.kotlinx.benchmark.runtime)
    // Cache backend benchmarks lease Valkey and NATS from the shared test-resource services.
    "jvmBenchmarkImplementation"(project(":bosca-core:test-support"))

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

configurations[jvmBenchmark.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[jvmBenchmark.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

/** Cache benchmarks need Docker-backed Valkey and NATS, so they run only under the `cache*` profiles. */
val CACHE_BENCHMARKS = "bosca\\.cache\\..*"

benchmark {
    targets {
        register("jvmBenchmark")
    }
    configurations {
        named("main") {
            exclude(".*LoadBenchmark.*")
            exclude(CACHE_BENCHMARKS)
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
            exclude(CACHE_BENCHMARKS)
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
            exclude(CACHE_BENCHMARKS)
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "s"
            mode = "thrpt"
            reportFormat = "json"
            advanced("jvmForks", 2)
        }
        register("websocket") {
            include(".*(WebSocket|Rfc8441).*Benchmark.*")
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
        register("websocketLoad") {
            include(".*(WebSocket|Rfc8441).*LoadBenchmark.*")
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "s"
            mode = "thrpt"
            reportFormat = "json"
            advanced("jvmForks", 2)
        }
        register("cache") {
            // One fork is enough: backend calls take 0.2-14 ms and serializer calls collect millions of samples per
            // iteration, so extra forks and iterations cost minutes without tightening the intervals meaningfully.
            include(CACHE_BENCHMARKS)
            exclude(".*LoadBenchmark.*")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "us"
            mode = "avgt"
            reportFormat = "json"
            advanced("jvmForks", 1)
        }
        register("cacheSmoke") {
            include(CACHE_BENCHMARKS)
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
        register("cacheLoad") {
            include("bosca\\.cache\\..*LoadBenchmark.*")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "s"
            mode = "thrpt"
            reportFormat = "json"
            advanced("jvmForks", 1)
        }
    }
}

tasks.withType<org.gradle.jvm.tasks.Jar>().matching { it.name == "jvmBenchmarkBenchmarkJar" }.configureEach {
    // The fat JMH jar includes signed third-party dependencies. Their signatures are invalid once entries are
    // repackaged, so omit signature metadata while retaining the signed classes themselves.
    eachFile {
        if (path.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA"))) {
            exclude()
        }
    }
}

tasks.named<Test>("test") {
    exclude("**/NettyServerTransportSoakTest.class")
}

val nettyTransportSoakTest by tasks.registering(Test::class) {
    description = "Runs real-socket HTTP/1, HTTP/2, and WebSocket transport leak tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/NettyServerTransportSoakTest.class")
    maxHeapSize = "2g"
    maxParallelForks = 1
    forkEvery = 1
    jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("io.netty.leakDetection.level", "paranoid")
    outputs.upToDateWhen { false }
    dependsOn(tasks.named("testClasses"))
}

tasks.named("check") {
    dependsOn(nettyTransportSoakTest)
}

kover {
    currentProject {
        sources {
            // Benchmarks are measurement harnesses, not production code, and no test executes them.
            excludedSourceSets.add(jvmBenchmark.name)
        }
    }
    reports {
        filters {
            excludes {
                annotatedBy("bosca.di.annotation.Generated")
                classes(
                    "bosca.server.netty.*Benchmark*",
                    "bosca.server.netty.Raw*",
                )
            }
        }
        verify {
            rule("core module — line coverage") {
                minBound(95)
            }
            rule("core module — branch coverage") {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 90
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

ksp {
    arg("ProviderRegistrarPrefix", "Core")
}
