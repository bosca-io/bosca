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
    testImplementation(libs.kotlin.test.junit)
    testImplementation(project(":bml:bml-compiler"))
    testImplementation(gradleTestKit())
}

bml {
    packageName.set("bosca.design.example.generated")
    metricsGlobalAssets.from(layout.projectDirectory.file("src/main/resources/design-example.css"))
}

application {
    mainClass.set("bosca.design.example.MainKt")
}

graalvmNative {
    testSupport.set(false)
    agent { enabled.set(false) }
    metadataRepository { enabled = true }
    binaries {
        named("main") {
            imageName.set("bml-design-consumer")
            mainClass.set("bosca.design.example.MainKt")
            buildArgs.addAll(listOf("--exclude-config", ".*/.*.jar", "^/META-INF/native-image/org\\.jline/.*"))
        }
    }
}

tasks.named("nativeRun") { outputs.upToDateWhen { false } }

val fixtureRuntime = configurations.named("runtimeClasspath")
val publishedFixturePlugin = configurations.create("publishedFixturePlugin") { isCanBeConsumed = false }
val publishedFixtureCompiler = configurations.create("publishedFixtureCompiler") {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
dependencies {
    add(publishedFixturePlugin.name, project(":bml:bml-gradle"))
    add(publishedFixturePlugin.name, "org.jetbrains.kotlin:kotlin-serialization:${libs.versions.kotlin.get()}")
    add(publishedFixtureCompiler.name, project(":bml:bml-compiler"))
    add(publishedFixtureCompiler.name, project(":bml:bml-annotations"))
}
tasks.withType<Test>().configureEach {
    dependsOn(tasks.jar, fixtureRuntime, publishedFixturePlugin, publishedFixtureCompiler,
        ":bml:bml-design:sourcesJar", ":bml:bml-design:generatePomFileForMavenPublication")
    inputs.dir(layout.projectDirectory.dir("src/test/browser"))
        .withPropertyName("browserChecks").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("src/test/types"))
        .withPropertyName("typeChecks").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("tsconfig.json"))
        .withPropertyName("typeScriptConfiguration").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("design.fixture.classpath", files(tasks.jar.flatMap { it.archiveFile }, fixtureRuntime).asPath)
    systemProperty("design.fixture.java", javaToolchains.launcherFor(java.toolchain).get().executablePath.asFile.absolutePath)
    systemProperty("design.fixture.node", bml.nodeExecutable.get())
    systemProperty("design.fixture.browser", layout.projectDirectory.file("src/test/browser/controls.mjs").asFile.absolutePath)
    systemProperty("design.fixture.typechecker", rootProject.layout.projectDirectory.file("bml/bml-runtime/node_modules/typescript/bin/tsc").asFile.absolutePath)
    systemProperty("design.fixture.tsconfig", layout.projectDirectory.file("tsconfig.json").asFile.absolutePath)
    inputs.files(publishedFixturePlugin, publishedFixtureCompiler).withPropertyName("publishedFixtureTools")
        .withNormalizer(ClasspathNormalizer::class.java)
    inputs.files(rootProject.fileTree("bml/bml-runtime/tools"), rootProject.fileTree("bml/bml-runtime/src"),
        rootProject.file("bml/bml-runtime/package-lock.json"))
        .withPropertyName("publishedFixtureClientToolchain").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("design.fixture.plugin", publishedFixturePlugin.asPath)
    systemProperty("design.fixture.compiler", publishedFixtureCompiler.asPath)
    systemProperty("design.fixture.version", project(":bml:bml-design").version.toString())
    systemProperty("design.fixture.pom", project(":bml:bml-design").layout.buildDirectory.file("publications/maven/pom-default.xml").get().asFile.absolutePath)
    inputs.file(project(":bml:bml-design").layout.buildDirectory.file("publications/maven/pom-default.xml"))
        .withPropertyName("publishedDesignPom").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("design.fixture.project", layout.projectDirectory.asFile.absolutePath)
    systemProperty("design.fixture.runtime", rootProject.layout.projectDirectory.dir("bml/bml-runtime").asFile.absolutePath)
    systemProperty("design.fixture.artifacts", (fixtureRuntime.get().resolvedConfiguration.resolvedArtifacts +
        publishedFixtureCompiler.resolvedConfiguration.resolvedArtifacts).distinctBy { it.file }.joinToString("\n") {
        "${it.moduleVersion.id.group}\t${it.moduleVersion.id.name}\t${it.moduleVersion.id.version}\t${it.classifier.orEmpty()}\t${it.file.absolutePath}"
    })
}

tasks.register<Test>("publishedConsumerTest") {
    group = "verification"
    description = "Compile and exercise the real Design Library from an isolated Maven repository."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("bosca.design.example.PublishedDesignLibraryTest") }
}

tasks.register<Test>("nativeConsumerTest") {
    group = "verification"
    description = "Exercise the standalone native consumer over HTTP and in an isolated browser."
    dependsOn("nativeCompile")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("bosca.design.example.DesignLibraryTest") }
    val binary = layout.buildDirectory.file("native/nativeCompile/bml-design-consumer" +
        if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else "")
    inputs.file(binary).withPropertyName("nativeConsumerBinary").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("design.fixture.binary", binary.get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}
