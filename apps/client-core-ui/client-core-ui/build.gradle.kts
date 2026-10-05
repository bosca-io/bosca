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

    jvm("desktop")

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
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
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
    add("kspCommonMainMetadata", project(":services-di:service-ksp"))
    add("kspCommonMainMetadata", project(":services-di:di-ksp"))
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
    if (name.startsWith("ksp") && name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

// The sources jar includes generated commonMain metadata, so its KSP producer must run first.
tasks.matching { it.name == "sourcesJar" || it.name.endsWith("SourcesJar") }.configureEach {
    dependsOn("kspCommonMainKotlinMetadata")
}

kotlin {
    cocoapods {
        version = "1.0"
        ios.deploymentTarget = "18.2"
    }
}
