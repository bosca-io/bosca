import java.util.concurrent.atomic.AtomicLong

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlinx.benchmark)
    id("io.bosca.bml")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

val jvmBenchmark by sourceSets.creating

dependencies {
    implementation(project(":bml:core-bml"))
    implementation(project(":bml:bml-annotations"))
    implementation(project(":bml:bml-server"))

    "jvmBenchmarkImplementation"(sourceSets.main.get().output)
    "jvmBenchmarkImplementation"(project(":bml:bml-compiler"))
    "jvmBenchmarkImplementation"(project(":bosca-core:core"))
    "jvmBenchmarkImplementation"(libs.kotlinx.benchmark.runtime)
    "jvmBenchmarkImplementation"(libs.kotlinx.coroutines.core)
}

configurations[jvmBenchmark.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[jvmBenchmark.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

benchmark {
    targets {
        register("jvmBenchmark")
    }
    configurations {
        named("main") {
            exclude(".*BmlHttpLoadBenchmark.*")
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
            exclude(".*BmlHttpLoadBenchmark.*")
            warmups = 1
            iterations = 2
            iterationTime = 200
            iterationTimeUnit = "ms"
            outputTimeUnit = "us"
            mode = "avgt"
            reportFormat = "json"
            advanced("jvmForks", 1)
        }
        register("load") {
            include(".*BmlHttpLoadBenchmark.*")
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "s"
            mode = "thrpt"
            reportFormat = "json"
            advanced("jvmForks", 2)
        }
        register("loadSmoke") {
            include(".*BmlHttpLoadBenchmark.*")
            warmups = 1
            iterations = 2
            iterationTime = 250
            iterationTimeUnit = "ms"
            outputTimeUnit = "s"
            mode = "thrpt"
            reportFormat = "json"
            advanced("jvmForks", 1)
        }
    }
}

tasks.withType<org.gradle.jvm.tasks.Jar>().matching { it.name == "jvmBenchmarkBenchmarkJar" }.configureEach {
    eachFile {
        if (path.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA"))) {
            exclude()
        }
    }
}

// The benchmark runner can log a JMH lock failure while Gradle still reports task success.
// Require a fresh report containing every scenario before treating either run as successful.
val latencyBenchmarkNames = setOf(
    "BmlCompilerBenchmark.generatePage",
    "BmlCompilerBenchmark.parsePage",
    "BmlHttpBenchmark.deferredFragment",
    "BmlHttpBenchmark.privatePage",
    "BmlHttpBenchmark.sharedPage",
    "BmlHttpBenchmark.sharedPageNotModified",
    "BmlHttpBenchmark.staticControl",
    "BmlRenderBenchmark.decodeDeferredRequest",
    "BmlRenderBenchmark.encodeDeferredProps",
    "BmlRenderBenchmark.renderCompiledPage",
)
val loadBenchmarkNames = setOf(
    "BmlHttpLoadBenchmark.deferredFragment",
    "BmlHttpLoadBenchmark.privatePage",
    "BmlHttpLoadBenchmark.sharedPage",
    "BmlHttpLoadBenchmark.staticControl",
)
val benchmarkProfiles = mapOf(
    "jvmBenchmarkBenchmark" to ("main" to latencyBenchmarkNames),
    "jvmBenchmarkSmokeBenchmark" to ("smoke" to latencyBenchmarkNames),
    "jvmBenchmarkLoadBenchmark" to ("load" to loadBenchmarkNames),
    "jvmBenchmarkLoadSmokeBenchmark" to ("loadSmoke" to loadBenchmarkNames),
)
tasks.matching { it.name in benchmarkProfiles }.configureEach {
    val startedAt = AtomicLong()
    doFirst { startedAt.set(System.currentTimeMillis()) }
    doLast {
        val (profile, benchmarkNames) = benchmarkProfiles.getValue(name)
        val reportDir = layout.buildDirectory.dir("reports/benchmarks/$profile").get().asFile
        val report = reportDir.walkTopDown()
            .filter { it.isFile && it.name == "jvmBenchmark.json" && it.lastModified() >= startedAt.get() - 1_000L }
            .maxByOrNull { it.lastModified() }
            ?: error("JMH did not write a fresh $profile benchmark report")
        val completed = Regex("\"benchmark\"\\s*:\\s*\"bosca\\.bml\\.benchmarks\\.([^\"]+)\"")
            .findAll(report.readText())
            .map { it.groupValues[1] }
            .toSet()
        val missing = benchmarkNames - completed
        check(missing.isEmpty()) { "JMH did not complete $profile benchmarks: ${missing.sorted()}" }
    }
}
