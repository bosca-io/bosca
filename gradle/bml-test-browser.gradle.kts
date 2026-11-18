// Applied only by projects whose acceptance tests launch Chromium.
val chromeOverride = providers.environmentVariable("BML_DESIGN_CHROME_BINARY")
val executableFile = project(":bml:bml-design-catalog").layout.buildDirectory
    .file("test-browser/executable.txt")
val browserPath = chromeOverride.orElse(providers.fileContents(executableFile).asText.map { it.trim() })
val browserProviders = providers

// A fresh application build has a different deployment identity, but exercises the same code.
normalization.runtimeClasspath { ignore("META-INF/bosca/bml/build-id") }

tasks.withType<Test>().configureEach {
    if (name != "firefoxTest" && (project.path != ":bml:bml-server" || name == "chunkCacheBrowserTest")) {
        inputs.files(rootProject.file("gradle/bml-test-browser.mjs"),
            rootProject.file("gradle/bml-test-browser.test.mjs"),
            rootProject.file("bml/bml-design-catalog/package-lock.json"))
            .withPropertyName("browserLauncher").withPathSensitivity(PathSensitivity.RELATIVE)
        if (!chromeOverride.isPresent) dependsOn(":bml:bml-design-catalog:prepareTestBrowser")
        val selectedBrowser = browserPath
        val executionProviders = browserProviders
        inputs.property("testBrowserVersion", selectedBrowser.map { executable ->
            executionProviders.exec { commandLine(executable, "--version") }.standardOutput.asText.get().trim()
        })
        inputs.property("testBrowserPlatform", "${System.getProperty("os.name")}/${System.getProperty("os.arch")}")
        inputs.file(rootProject.file("gradle/bml-test-browser.gradle.kts"))
            .withPropertyName("browserTestSetup").withPathSensitivity(PathSensitivity.RELATIVE)
        doFirst {
            val executable = selectedBrowser.get()
            environment("BML_DESIGN_CHROME_BINARY", executable)
            systemProperty("bml.test.browser", executable)
        }
    }
}
