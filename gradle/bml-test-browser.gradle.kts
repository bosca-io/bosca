// Applied only by projects whose acceptance tests launch Chromium.
// An explicit executable (including CI's provisioned browser) takes precedence.
val chromeOverride = providers.environmentVariable("BML_DESIGN_CHROME_BINARY")
val executableFile = project(":bml:bml-design-catalog").layout.buildDirectory
    .file("test-browser/executable.txt").get().asFile
tasks.withType<Test>().configureEach {
    if (name != "firefoxTest") {
        if (chromeOverride.isPresent) {
            inputs.property("testBrowserOverride", chromeOverride.get())
        } else {
            dependsOn(":bml:bml-design-catalog:prepareTestBrowser")
            inputs.file(executableFile).withPropertyName("managedTestBrowser")
                .withPathSensitivity(PathSensitivity.NONE)
            val browserPathFile = executableFile
            doFirst {
                environment("BML_DESIGN_CHROME_BINARY", browserPathFile.readText().trim())
            }
        }
    }
}
