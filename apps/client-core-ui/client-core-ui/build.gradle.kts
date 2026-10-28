@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.compose.resources.ResourcesExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

// client-core — the shared Bosca client foundation (auth UI/VMs, platform, preferences, navigation,
// theme, models + the GraphQL data layer). The GraphQL layer is built on
// io.bosca:bosca-graphql-client (NOT Apollo).
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.native.cocoapods")
    alias(libs.plugins.kotlin.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
}

// JVM validation generates providers on desktop to avoid resolving native metadata and CocoaPods.
val jvmCodegen = providers.gradleProperty("bosca.kmp.jvmCodegen").map(String::toBoolean).getOrElse(false)
val providerKspConfiguration = if (jvmCodegen) "kspDesktopProviders" else "kspCommonMainMetadata"
val providerKspTask = if (jvmCodegen) "kspProvidersKotlinDesktop" else "kspCommonMainKotlinMetadata"

val androidEnabled = gradle.extra["androidEnabled"] as Boolean
if (androidEnabled) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

compose.resources {
    packageOfResClass = "bosca.core"
    generateResClass = ResourcesExtension.ResourceClassGeneration.Auto
}

kotlin {
    applyDefaultHierarchyTemplate {
        common {
            group("web") {
                withJs()
                withWasmJs()
            }
        }
    }
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }

    if (androidEnabled) {
        extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
            namespace = "bosca.core"
            compileSdk = 37
            androidResources.enable = true
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

    js {
        browser()
        binaries.executable()
    }

    wasmJs {
        browser()
        binaries.executable()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            baseName = "BoscaCoreKit"
            freeCompilerArgs += listOf("-Xbinary=bundleId=bosca.core.kit")
            isStatic = true
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
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.jetbrains.material3.adaptiveNavigation3)
            implementation(libs.jetbrains.lifecycle.viewmodelNavigation3)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.paging.common)
            implementation(libs.paging.compose)

            api(libs.coil.compose)
            api(libs.coil.network.ktor)

            api(project(":services-di:service"))
            api(project(":services-di:di"))
            api(project(":bosca-kmp:dom-shared"))
            api(project(":bosca-kmp:auth-shared"))
            api(project(":bosca-kmp:client-core"))
            api(project(":bosca-graphql:bosca-graphql-client")) // the GraphQL data layer (replaces Apollo)
        }

        if (androidEnabled) {
            val androidMain by getting {
                dependencies {
                    implementation(libs.androidx.security.crypto)
                    api(libs.ktor.client.android)
                }
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                api(libs.ktor.client.java)
            }
        }

        val iosMain by getting {
            dependencies {
                api(libs.ktor.client.darwin)
            }
        }

        val webMain by getting {
            dependencies {
                implementation(libs.kotlinx.browser)
                api(libs.ktor.client.js)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    add(providerKspConfiguration, project(":services-di:service-ksp"))
    add(providerKspConfiguration, project(":services-di:di-ksp"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

ksp {
    arg("ProviderRegistrarPrefix", "Core")
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

kotlin {
    cocoapods {
        version = "1.0"
        ios.deploymentTarget = "18.2"
    }
}
