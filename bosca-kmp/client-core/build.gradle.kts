@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.targets.native.tasks.PodBuildTask

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
    id("org.jetbrains.kotlin.native.cocoapods")
    id("io.bosca.graphql") // typed GraphQL data layer codegen (replaces Apollo)
}

val iosDeploymentTarget = "18.2"
val androidEnabled = gradle.extra["androidEnabled"] as Boolean
if (androidEnabled) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
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
            compileSdk = 36
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
            kotlin.srcDir("build/generated/bosca-graphql/kotlin") // generated typed GraphQL operations
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)

            api(project(":services-di:service"))
            api(project(":services-di:di"))
            api(project(":bosca-kmp:dom-shared"))
            api(project(":bosca-graphql:bosca-graphql-client")) // the GraphQL data layer (replaces Apollo)
            api(project(":bosca-kmp:auth-shared"))
        }

        if (androidEnabled) {
            val androidMain by getting {
                dependencies {
                    implementation(libs.androidx.security.crypto)
                    implementation(libs.firebase.messaging.base)
                    api(libs.ktor.client.android)
                }
            }
        }

        val desktopMain by getting {
            dependencies {
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
            implementation(libs.ktor.client.mock)
        }
    }
}

dependencies {
    "boscaGraphqlGenerator"(project(":bosca-graphql:bosca-graphql-client"))
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

kotlin {
    cocoapods {
        version = "1.0"
        ios.deploymentTarget = iosDeploymentTarget

        // auth-shared is an API dependency. Kotlin/Native propagates its
        // `-framework GoogleSignIn` linker option, but not the CocoaPods search
        // path into this framework's final link, so declare the pod here too.
        pod("GoogleSignIn")
    }
}

tasks.withType<PodBuildTask>().configureEach {
    xcodeBuildSettings.put("IPHONEOS_DEPLOYMENT_TARGET", iosDeploymentTarget)
}

// GraphQL data layer codegen. Scalars carry explicit (native-safe) kotlinx serializers — UUID/DateTime via
// bosca.core.platform.graphql.*, JSON as JsonElement, Upload via the client's multipart Upload type. The
// operations + schema live under src/commonMain/graphql (KMP layout, not the plugin's JVM default).
boscaGraphql {
    schemaFile.set(layout.projectDirectory.file("src/commonMain/graphql/schema.graphqls"))
    sourceDir.set(layout.projectDirectory.dir("src/commonMain/graphql"))
    packageName.set("bosca.core.graphql")
    scalarMappings.put("JSON", "kotlinx.serialization.json.JsonElement")
    scalarMappings.put("Long", "kotlin.Long")
    scalarMappings.put("UUID", "kotlin.uuid.Uuid::bosca.core.platform.graphql.UuidSerializer")
    scalarMappings.put("DateTime", "kotlin.time.Instant::bosca.core.platform.graphql.InstantSerializer")
    scalarMappings.put("Upload", "bosca.graphql.client.Upload::bosca.graphql.client.UploadSerializer")
}

// The io.bosca.graphql plugin auto-wires its output into a Kotlin/JVM `main` source set; client-core is KMP,
// so the generated dir is added to commonMain (above) and every Kotlin compile + KSP task must run codegen first.
tasks.matching {
    (it.name.startsWith("compile") && it.name.contains("Kotlin")) || it.name.startsWith("ksp")
}.configureEach {
    dependsOn("generateBoscaGraphqlClient")
}

// Publish source jars also collect the generated commonMain dirs (graphql codegen + KSP metadata), so they
// must run those producers first — Gradle 9 fails the build on an undeclared implicit dependency otherwise.
tasks.matching { it.name == "sourcesJar" || it.name.endsWith("SourcesJar") }.configureEach {
    dependsOn("generateBoscaGraphqlClient", "kspCommonMainKotlinMetadata")
}
