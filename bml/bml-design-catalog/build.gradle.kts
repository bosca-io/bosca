import java.io.File

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("io.bosca.bml")
    id("org.graalvm.buildtools.native")
    application
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    implementation(project(":bml:bml-design"))
    implementation(project(":bml:bml-server"))
    implementation(project(":bml:bml-annotations"))
    implementation(project(":bosca-core:core-graalvm"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit.platform.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.platform.vintage)
}

bml {
    packageName.set("bosca.design.catalog.generated")
    metricsGlobalAssets.from(layout.projectDirectory.file("src/main/resources/catalog.css"))
}

application {
    mainClass.set("bosca.design.catalog.MainKt")
}

graalvmNative {
    testSupport.set(false)
    agent { enabled.set(false) }
    metadataRepository { enabled = true }
    binaries {
        named("main") {
            imageName.set("bml-design-catalog")
            mainClass.set("bosca.design.catalog.MainKt")
            buildArgs.add("-O3")
            buildArgs.addAll(providers.gradleProperty("bosca.native.march")
                .map { listOf("-march=$it") }.orElse(emptyList()))
            buildArgs.addAll(listOf("--exclude-config", ".*/.*.jar", "^/META-INF/native-image/org\\.jline/.*"))
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) buildArgs.add("--gc=G1")
        }
    }
}

tasks.named("nativeRun") { outputs.upToDateWhen { false } }

tasks.register<Exec>("prepareTestBrowser") {
    group = "verification"
    description = "Install the pinned Chromium used by workspace browser acceptance tests."
    workingDir(layout.projectDirectory)
    commandLine(bml.nodeExecutable.get(), "src/test/browser/prepare-chromium.mjs",
        layout.buildDirectory.file("test-browser/executable.txt").get().asFile.absolutePath)
    inputs.files("package.json", "package-lock.json", "src/test/browser/prepare-chromium.mjs")
    outputs.file(layout.buildDirectory.file("test-browser/executable.txt"))
    // Reinstall only when inputs change or the external browser/dependencies disappear.
    val browserMarker = layout.buildDirectory.file("test-browser/executable.txt").get().asFile
    val installedPlaywright = layout.projectDirectory.file("node_modules/playwright-core/package.json").asFile
    outputs.upToDateWhen {
        installedPlaywright.isFile && browserMarker.isFile &&
            File(browserMarker.readText().trim()).canExecute()
    }
}

apply(from = rootProject.file("gradle/bml-test-browser.gradle.kts"))

val catalogRuntime = configurations.named("runtimeClasspath")
val catalogClasspath = files(tasks.jar.flatMap { it.archiveFile }, catalogRuntime).asPath
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Only explicitly concurrent browser classes opt in. Cap compensation threads too:
    // blocking HTTP calls can otherwise grow the pool beyond its target parallelism.
    val browserWorkers = providers.gradleProperty("bml.browser.workers").getOrElse("4")
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", browserWorkers)
    systemProperty("junit.jupiter.execution.parallel.config.fixed.max-pool-size", browserWorkers)
    dependsOn(tasks.jar, catalogRuntime)
    inputs.dir(layout.projectDirectory.dir("src/test/browser"))
        .withPropertyName("browserChecks").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("package-lock.json"))
        .withPropertyName("accessibilityDependency").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(tasks.jar.flatMap { it.archiveFile }, catalogRuntime)
        .withPropertyName("applicationRuntime").withNormalizer(ClasspathNormalizer::class.java)
    // Absolute launch paths are runtime arguments; content inputs above determine reuse.
    val testNode = bml.nodeExecutable.get()
    inputs.property("testNodeVersion", providers.exec {
        commandLine(testNode, "--version")
    }.standardOutput.asText.map { it.trim() })
    val runtimeProperties = mapOf(
        "catalog.classpath" to catalogClasspath,
        "catalog.java" to javaToolchains.launcherFor(java.toolchain).get().executablePath.asFile.absolutePath,
        "catalog.node" to bml.nodeExecutable.get(),
        "catalog.browser" to layout.projectDirectory.file("src/test/browser/catalog.mjs").asFile.absolutePath,
        "catalog.firefoxBrowser" to layout.projectDirectory.file("src/test/browser/firefox-tree.mjs").asFile.absolutePath,
    )
    doFirst { systemProperties(runtimeProperties) }
}

val firefoxTreeTest = "bosca.design.catalog.CatalogApplicationTest.Firefox trees retain native keyboard navigation"
val nativeCatalogTest = "bosca.design.catalog.CatalogApplicationTest.native executable serves production resources and interactions without a workspace"
tasks.test { filter { excludeTestsMatching(firefoxTreeTest); excludeTestsMatching(nativeCatalogTest) } }

tasks.register<Test>("nativeCatalogTest") {
    group = "verification"
    description = "Exercise the standalone native catalog with production assets and isolated browser actions."
    dependsOn("nativeCompile", tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching(nativeCatalogTest) }
    val binary = layout.buildDirectory.file("native/nativeCompile/bml-design-catalog" +
        if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else "")
    inputs.file(binary).withPropertyName("nativeCatalogBinary").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("catalog.binary", binary.get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}

tasks.register<Test>("browserTest") {
    group = "verification"
    description = "Verify the packaged catalog over HTTP and in an isolated browser."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("bosca.design.catalog.CatalogApplicationTest")
        excludeTestsMatching(firefoxTreeTest)
        excludeTestsMatching(nativeCatalogTest)
    }
}

tasks.register<Test>("firefoxTest") {
    group = "verification"
    description = "Qualify tree behavior in an installed Firefox using an isolated headless profile."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching(firefoxTreeTest) }
    outputs.upToDateWhen { false }
    outputs.cacheIf("Qualifies the locally installed Firefox version") { false }
}

tasks.register<Test>("accessibilityTest") {
    group = "verification"
    description = "Run full-document axe checks against the packaged catalog in both runtime modes."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("bosca.design.catalog.CatalogApplicationTest.*accessibility*") }
}

val typecheckClient by tasks.registering(Exec::class) {
    group = "verification"
    description = "Type-check catalog islands against the runtime and linked library declarations."
    dependsOn("compileKotlin", "bmlPrepareClient")
    commandLine(bml.nodeExecutable.get(), rootProject.layout.projectDirectory.file("bml/bml-runtime/node_modules/typescript/bin/tsc").asFile,
        "--project", layout.projectDirectory.file("tsconfig.json").asFile)
}

tasks.check { dependsOn(typecheckClient) }
