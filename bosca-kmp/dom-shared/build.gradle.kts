@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.plugin.serialization)
}

apply(plugin = "com.android.kotlin.multiplatform.library")

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }

    extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
        namespace = "io.bosca.documents"
        compileSdk = 36
    }

    jvm("desktop")

    // JS/Wasm variants consumed by bosca-kmp client-core and web clients.
    js {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
                enabled = false
            }
        }
        nodejs()
        binaries.executable()
    }

    wasmJs {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
                enabled = false
            }
        }
        nodejs()
        binaries.executable()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "BoscaDomSharedKit"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":bosca-kmp:bible-dom-shared"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
