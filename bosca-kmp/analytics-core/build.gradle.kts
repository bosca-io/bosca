@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.targets.native.tasks.PodBuildTask

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.compose.multiplatform)
    id("org.jetbrains.kotlin.native.cocoapods")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.androidx.room3)
    alias(libs.plugins.kover)
}

val iosDeploymentTarget = "18.2"
// JVM validation generates providers on desktop to avoid resolving native metadata and CocoaPods.
val jvmCodegen = providers.gradleProperty("bosca.kmp.jvmCodegen").map(String::toBoolean).getOrElse(false)
val providerKspConfiguration = if (jvmCodegen) "kspDesktopProviders" else "kspCommonMainMetadata"
val providerKspTask = if (jvmCodegen) "kspProvidersKotlinDesktop" else "kspCommonMainKotlinMetadata"

val androidEnabled = gradle.extra["androidEnabled"] as Boolean
if (androidEnabled) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

val iosEnabled = gradle.extra["iosEnabled"] as Boolean

kotlin {
    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    if (androidEnabled) {
        extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
            namespace = "io.bosca.analytics"
            compileSdk = 36
            minSdk = 23
        }
    }

    jvm("desktop") {
        if (jvmCodegen) {
            // A generation-only compilation keeps providers in commonMain without mixing in
            // platform-specific generators such as Room's expect/actual implementations.
            val mainCompilation = compilations.getByName("main")
            val commonSources = this@kotlin.sourceSets.getByName("commonMain").kotlin
            compilations.create("providers") {
                defaultSourceSet.kotlin.srcDirs(commonSources.sourceDirectories)
                compileDependencyFiles = mainCompilation.compileDependencyFiles
            }
        }
    }

    if (iosEnabled) {
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
        ).forEach {
            it.binaries.framework {
                baseName = "BoscaAnalyticsKit"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(
                if (jvmCodegen) "build/generated/ksp/desktop/desktopProviders/kotlin"
                else "build/generated/ksp/metadata/commonMain/kotlin",
            )
        }
        commonMain.dependencies {
            api(project(":bosca-kmp:client-core"))
            api(project(":services-di:di"))
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            api(libs.compose.runtime)
            api(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }

        if (androidEnabled) {
            val androidMain by getting {
                dependencies {
                    api(libs.ktor.client.android)
                }
            }
        }

        val desktopMain by getting {
            dependencies {
                api(libs.ktor.client.java)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(libs.compose.ui.test)
                implementation(compose.desktop.currentOs)
            }
        }

        if (iosEnabled) {
            val iosMain by getting {
                dependencies {
                    api(libs.ktor.client.darwin)
                }
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }

    if (iosEnabled) {
        cocoapods {
            version = "1.0"
            ios.deploymentTarget = iosDeploymentTarget
            // client-core exposes auth-shared, whose native implementation links
            // GoogleSignIn. The final analytics framework/test binary must own the
            // pod search path as well as the transitive linker option.
            pod("GoogleSignIn")
            framework {
                baseName = "BoscaAnalyticsKit"
                isStatic = true
            }
        }
    }
}

tasks.withType<PodBuildTask>().configureEach {
    xcodeBuildSettings.put("IPHONEOS_DEPLOYMENT_TARGET", iosDeploymentTarget)
}

dependencies {
    add(providerKspConfiguration, project(":services-di:di-ksp"))
    if (androidEnabled) {
        add("kspAndroid", libs.androidx.room3.compiler)
    }
    add("kspDesktop", libs.androidx.room3.compiler)
    if (iosEnabled) {
        add("kspIosArm64", libs.androidx.room3.compiler)
        add("kspIosSimulatorArm64", libs.androidx.room3.compiler)
    }
}

ksp {
    arg("ProviderRegistrarPrefix", "Analytics")
    arg("enableSerializersRegistrar", "false")
    arg("enableSerializerRegistrar", "false")
}

tasks.configureEach {
    if (name.startsWith("ksp") && name != providerKspTask) {
        dependsOn(providerKspTask)
    }
}

// Source jars must run the selected provider generator before collecting its output.
tasks.matching { it.name == "sourcesJar" || it.name.endsWith("SourcesJar") }.configureEach {
    dependsOn(providerKspTask)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

kover {
    if (jvmCodegen) {
        currentProject {
            sources {
                // KSP analyzes main's sources in this compilation; coverage comes from main's bytecode.
                excludedSourceSets.add("providers")
            }
        }
    }
    reports {
        filters {
            excludes {
                annotatedBy("bosca.di.annotation.Generated")
                classes(
                    "bosca.analytics.persistence.room.AnalyticsDatabase_Impl*",
                    "bosca.analytics.persistence.room.AnalyticsDatabaseConstructor*",
                    "bosca.analytics.persistence.room.AnalyticsStorageDao_Impl*",
                )
            }
        }
        variant("desktop") {
            verify {
                rule("Line coverage") {
                    minBound(95)
                }
                rule("Branch coverage") {
                    bound {
                        coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                        minValue = 90
                    }
                }
            }
        }
    }
}

tasks.named("koverVerify") {
    dependsOn("koverVerifyDesktop")
}

val verifyAnalyticsProviderBindings by tasks.registering {
    dependsOn(providerKspTask)
    val registrar = layout.buildDirectory.file(
        (if (jvmCodegen) "generated/ksp/desktop/desktopProviders/kotlin" else "generated/ksp/metadata/commonMain/kotlin") +
            "/bosca/di/AnalyticsProviderRegistrar.kt",
    )
    inputs.file(registrar)
    doLast {
        val source = registrar.get().asFile.readText()
        // Named and unnamed providers occupy separate registry slots.
        val bindings = Regex("ProviderRegistry\\.register\\(([^,]+)::class[^\\n]*")
            .findAll(source)
            .map {
                val type = it.groupValues[1].trim()
                val name = Regex(", \"([^\"]+)\", (?:true|false)\\)").find(it.value)?.groupValues?.get(1)
                type to name
            }
            .toList()
        val duplicates = bindings.groupingBy { it }.eachCount().filterValues { it > 1 }
        check(duplicates.isEmpty()) { "Generated analytics registrar contains duplicate bindings: $duplicates" }
        check(bindings.count { it.first == "AnalyticsService" } == 1) {
            "Generated analytics registrar must contain exactly one AnalyticsService binding"
        }
        check(bindings.count { it.first == "AnalyticsLifecycle" } == 1) {
            "Generated analytics registrar must contain exactly one AnalyticsLifecycle binding"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyAnalyticsProviderBindings)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Bootstrap validation: compile this KMP runtime's own sources with the sibling compiler plugin.
// This exercises common metadata, desktop, Android, and Native IR compatibility on normal builds.
val analyticsCompilerValidation by configurations.creating
dependencies {
    analyticsCompilerValidation(project(":bosca-kmp:analytics-compiler"))
}
tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(":bosca-kmp:analytics-compiler:jar")
    val compilerPluginPath = providers.provider {
        analyticsCompilerValidation.files.single { it.name.startsWith("analytics-compiler-") }.absolutePath
    }
    compilerOptions.freeCompilerArgs.addAll(
        compilerPluginPath.map { path ->
            listOf(
                "-Xplugin=$path",
                "-P",
                "plugin:bosca.analytics:enabled=true",
            )
        },
    )
}
