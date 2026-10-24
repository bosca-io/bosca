import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "io.bosca.bml"
version = "0.0.1"

dependencies {
    // Share the parser, import rules, and manifest contract without pulling the server runtime
    // or a second Kotlin compiler into the IDE. Compiler APIs remain supplied by the IDE.
    implementation(project(":bml:bml-compiler")) { isTransitive = false }
    implementation(project(":bml:core-bml")) { isTransitive = false }
    implementation(libs.kotlinx.serialization.json)
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
    pluginVerification {
        ides {
            current()
            providers.gradleProperty("bml.ideVerificationPath").orNull?.let { local(it) }
        }
    }
    pluginConfiguration {
        name = "Bosca BML"
        ideaVersion {
            sinceBuild = "261"
            // Compatibility beyond the configured SDK is qualified separately.
        }
    }
}

kotlin {
    jvmToolchain(25)
}

val designBinary = project(":bml:bml-design").tasks.named<Jar>("jar")
val designSources = project(":bml:bml-design").tasks.named<Jar>("sourcesJar")
tasks.test {
    dependsOn(designBinary, designSources)
    inputs.files(designBinary.flatMap { it.archiveFile }, designSources.flatMap { it.archiveFile })
        .withPropertyName("designLibraryArtifacts").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("bml.ide.design.binary", designBinary.get().archiveFile.get().asFile.absolutePath)
    systemProperty("bml.ide.design.sources", designSources.get().archiveFile.get().asFile.absolutePath)
}
