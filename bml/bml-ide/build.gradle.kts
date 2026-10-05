import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "io.bosca.bml"
version = "0.0.1"

dependencies {
    intellijPlatform {
        // IntelliJ IDEA ULTIMATE 2026.1.2. Ultimate (not Community) because it
        // bundles JavaScript/TypeScript: the <script client> injection behavior (auth globals, object
        // literal checking, reference-lib directives) is only testable against the real TS plugin.
        // useInstaller = false resolves the published Maven artifact (the installer distribution
        // isn't always available for the exact patch release).
        intellijIdeaUltimate("2026.1.2") {
            useInstaller = false
        }
        bundledPlugin("org.jetbrains.kotlin")
        // CSS support — for class="…" → CSS-class references (BmlCssClassRefs). Optional at runtime
        // (depends optional in plugin.xml); needed at compile time for com.intellij.psi.css.CssClass.
        bundledPlugin("com.intellij.css")
        // JavaScript/TypeScript — for the <script client> injection tests (optional at runtime).
        bundledPlugin("JavaScript")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(libs.junit)
}

intellijPlatform {
    pluginConfiguration {
        name = "Bosca BML"
        ideaVersion {
            sinceBuild = "261"
            // untilBuild left at the SDK default branch cap for v1.
        }
    }
}

kotlin {
    jvmToolchain(25)
}
