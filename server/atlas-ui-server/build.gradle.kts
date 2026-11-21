import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kover)
    application
    id("io.bosca.bml.host")
    id("org.graalvm.buildtools.native")
}

description = "Bosca Atlas BML host and same-origin proxies"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    api(project(":server:atlas-ui"))
    implementation(project(":git:git-ui"))
    implementation(project(":artifacts:artifacts-ui"))
    implementation(project(":workops:workops-ui"))
    kover(project(":workops:workops-ui"))
    implementation(project(":admin-support:system-ui"))
    implementation(project(":git:git-ui-server"))
    implementation(project(":bosca-core:core-graalvm"))
    runtimeOnly(libs.logback.classic)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":bosca-core:test-support"))
}

application {
    mainClass.set("bosca.atlas.ui.server.ApplicationKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") { outputs.upToDateWhen { false } }

kover {
    reports {
        total {
            additionalBinaryReports.addAll(providers.gradleProperty("atlas.workops.coverage.directory").map { path ->
                val directory = File(path)
                require(directory.isDirectory) { "The WorkOps process coverage directory is unavailable: $path" }
                val reports = directory.listFiles { file -> file.isFile && file.extension == "ic" }?.toList().orEmpty()
                require(reports.isNotEmpty() && reports.all { it.length() > 0 }) { "WorkOps process coverage must be complete before generating the aggregate report." }
                require(reports.none { File(it.absolutePath + ".lck").exists() }) { "WorkOps process coverage contains an unfinished report; rerun the affected fixture before merging coverage." }
                reports
            }.orElse(emptyList()))
            verify {
                // This aggregate also consumes the explicitly selected production-process reports.
                onCheck.set(providers.gradleProperty("atlas.workops.coverage.directory").map { true }.orElse(false))
                rule("WorkOps UI branch coverage") {
                    bound {
                        coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                        minValue = 90
                    }
                }
            }
        }
        filters {
            includes { classes("bosca.workops.ui.*") }
            excludes { classes("bosca.workops.ui.generated.*", "bosca.workops.ui.graphql.*") }
        }
    }
}

graalvmNative {
    testSupport.set(false)
    agent { enabled.set(false) }
    metadataRepository { enabled = true }
    binaries {
        named("main") {
            imageName.set("bosca-atlas-ui-server")
            mainClass.set("bosca.atlas.ui.server.ApplicationKt")
            buildArgs.add("-O3")
            // The JDK build-time timezone registry retains this provider and its constant value graph.
            buildArgs.add("--initialize-at-build-time=" + listOf(
                "net.fortuna.ical4j.model.CalendarDateFormat",
                "net.fortuna.ical4j.model.CalendarDateFormat\$InstantTemporalQuery",
                "net.fortuna.ical4j.model.CalendarDateFormat\$LocalDateTemporalQuery",
                "net.fortuna.ical4j.model.CalendarDateFormat\$LocalDateTimeTemporalQuery",
                "net.fortuna.ical4j.model.CalendarDateFormat\$OffsetDateTimeTemporalQuery",
                "net.fortuna.ical4j.model.ComponentList",
                "net.fortuna.ical4j.model.DefaultZoneRulesProvider",
                "net.fortuna.ical4j.model.ParameterList",
                "net.fortuna.ical4j.model.PropertyList",
                "net.fortuna.ical4j.model.TemporalAdapter",
                "net.fortuna.ical4j.model.TimeZoneLoader",
                "net.fortuna.ical4j.model.TimeZoneRegistry",
                "net.fortuna.ical4j.model.TimeZoneUpdater",
                "net.fortuna.ical4j.model.ZoneOffsetAdapter",
                "net.fortuna.ical4j.model.component.Standard",
                "net.fortuna.ical4j.model.parameter.Value",
                "net.fortuna.ical4j.model.property.DtStart",
                "net.fortuna.ical4j.model.property.TzOffsetFrom",
                "net.fortuna.ical4j.model.property.TzOffsetTo",
                "net.fortuna.ical4j.util.MapTimeZoneCache",
            ).joinToString(","))
            buildArgs.add("-H:+IncludeAllLocales")
            buildArgs.addAll(providers.gradleProperty("bosca.native.march").map { listOf("-march=$it") }.orElse(emptyList()))
            providers.gradleProperty("bosca.native.buildHeap").orNull?.let { jvmArgs("-Xmx$it") }
            buildArgs.addAll(providers.gradleProperty("bosca.native.parallelism").map { listOf("--parallelism=$it") }.orElse(emptyList()))
            buildArgs.addAll(listOf("--exclude-config", ".*/.*.jar", "^/META-INF/native-image/org[.]jline/.*"))
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) buildArgs.add("--gc=G1")
        }
    }
}

tasks.register<Zip>("nativeDistZip") {
    group = "distribution"
    description = "Package the native Atlas UI host and its generated runtime libraries."
    dependsOn("nativeCompile")
    archiveFileName.set("atlas-ui-server-native.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(tasks.named<BuildNativeImageTask>("nativeCompile").flatMap { it.outputDirectory }) {
        include("bosca-atlas-ui-server", "bosca-atlas-ui-server.exe", "*.so", "*.so.*", "*.dylib", "*.dll", "*.jnilib")
        filesMatching(listOf("bosca-atlas-ui-server", "bosca-atlas-ui-server.exe")) {
            permissions { unix("755") }
        }
    }
}
