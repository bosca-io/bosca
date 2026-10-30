// Notifications — the public email-preference site built with BML. The io.bosca.bml plugin generates
// a Kotlin render object per `.bml` under src/main/bml/ AND (via the io.bosca.graphql plugin it
// applies) the typed GraphQL data layer from src/main/graphql/. Runs on the dedicated SSR server.
//
//     ./gradlew :notifications-web:run                  # -> http://localhost:9094/  (from the workspace root)
//     ./gradlew :notifications-web:downloadBoscaGraphqlSchema   # refresh src/main/graphql/schema.graphqls
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("io.bosca.bml")     // .bml render codegen; also contributes BML-sane defaults to io.bosca.graphql
    id("io.bosca.graphql") // owns the GraphQL data-layer codegen (download-schema + generate)
    application
    id("org.graalvm.buildtools.native") // deployed as a GraalVM native image (nativeCompile → Dockerfile)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(project(":bml:bml-design"))
    "boscaGraphqlGenerator"(project(":bosca-graphql:bosca-graphql-client"))
    implementation(project(":bml:core-bml"))          // RenderContext / HtmlWriter (generated code calls these)
    implementation(project(":bml:bml-annotations"))   // @BmlPage / @BmlGenerated (generated code references these)
    implementation(project(":bml:bml-server"))        // BmlServer — run the site as an actual SSR server
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)  // @Serializable island state models
    implementation(project(":bosca-graphql:bosca-graphql-client"))        // the generated operations reference BoscaOperation / GraphQLJson
    implementation(project(":bosca-core:core-graalvm"))          // BoscaFeature + shared native-image metadata for the bosca-core Netty stack
    runtimeOnly(libs.logback.classic)                // logging backend for the deployed server (tests inherit it)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test) // kotlinx-coroutines-test — suspend page loaders
    testImplementation(gradleTestKit())
}

apply(from = rootProject.file("gradle/bml-test-browser.gradle.kts"))

val adoptionRuntime = configurations.named("runtimeClasspath")
val adoptionPlugin = configurations.create("adoptionPlugin") { isCanBeConsumed = false }
val adoptionCompiler = configurations.create("adoptionCompiler") {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
dependencies {
    add(adoptionPlugin.name, project(":bml:bml-gradle"))
    add(adoptionPlugin.name, "org.jetbrains.kotlin:kotlin-serialization:${libs.versions.kotlin.get()}")
    add(adoptionCompiler.name, project(":bml:bml-compiler"))
    add(adoptionCompiler.name, project(":bml:bml-annotations"))
}
tasks.test {
    filter { excludeTestsMatching("bosca.notifications.web.NativeDesignAdoptionTest") }
    dependsOn(tasks.jar, adoptionRuntime, adoptionPlugin, adoptionCompiler, ":bml:bml-design:generatePomFileForMavenPublication")
    inputs.dir(layout.projectDirectory.dir("src/test/browser"))
        .withPropertyName("adoptionBrowserChecks").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(adoptionPlugin, adoptionCompiler).withPropertyName("adoptionTools").withNormalizer(ClasspathNormalizer::class.java)
    inputs.files(rootProject.fileTree("bml/bml-runtime/tools"), rootProject.fileTree("bml/bml-runtime/src"),
        rootProject.file("bml/bml-runtime/package-lock.json"))
        .withPropertyName("adoptionClientToolchain").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(tasks.jar.flatMap { it.archiveFile }, adoptionRuntime)
        .withPropertyName("applicationRuntime").withNormalizer(ClasspathNormalizer::class.java)
    // Absolute launch paths are runtime arguments; content inputs above determine reuse.
    inputs.dir(layout.projectDirectory.dir("src/main"))
        .withPropertyName("publishedConsumerSources").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("adoption.version", project(":bml:bml-design").version.toString())
    inputs.file(project(":bml:bml-design").layout.buildDirectory.file("publications/maven/pom-default.xml"))
        .withPropertyName("publishedDesignPom").withPathSensitivity(PathSensitivity.NONE)
    val testNode = bml.nodeExecutable.get()
    inputs.property("testNodeVersion", providers.exec {
        commandLine(testNode, "--version")
    }.standardOutput.asText.map { it.trim() })
    val runtimeProperties = mapOf(
        "adoption.classpath" to files(tasks.jar.flatMap { it.archiveFile }, adoptionRuntime).asPath,
        "adoption.java" to javaToolchains.launcherFor(java.toolchain).get().executablePath.asFile.absolutePath,
        "adoption.node" to bml.nodeExecutable.get(),
        "adoption.project" to projectDir.absolutePath,
        "adoption.runtime" to rootProject.file("bml/bml-runtime").absolutePath,
        "adoption.plugin" to adoptionPlugin.asPath,
        "adoption.compiler" to adoptionCompiler.asPath,
        "adoption.pom" to project(":bml:bml-design").layout.buildDirectory.file("publications/maven/pom-default.xml").get().asFile.absolutePath,
        "adoption.artifacts" to (adoptionRuntime.get().resolvedConfiguration.resolvedArtifacts +
            adoptionCompiler.resolvedConfiguration.resolvedArtifacts).distinctBy { it.file }.joinToString("\n") {
            "${it.moduleVersion.id.group}\t${it.moduleVersion.id.name}\t${it.moduleVersion.id.version}\t${it.classifier.orEmpty()}\t${it.file.absolutePath}"
        },
    )
    doFirst { systemProperties(runtimeProperties) }
}

tasks.register<Test>("nativeDesignAdoptionTest") {
    group = "verification"
    description = "Exercise the Notifications native executable with shared design controls and isolated API/browser fixtures."
    dependsOn(tasks.testClasses, "nativeCompile")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("bosca.notifications.web.NativeDesignAdoptionTest") }
    useJUnitPlatform()
    val executableName = "notifications-web" + if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else ""
    val executable = layout.buildDirectory.file("native/nativeCompile/$executableName")
    inputs.file(executable).withPropertyName("nativeExecutable").withPathSensitivity(PathSensitivity.NONE)
    inputs.dir(layout.projectDirectory.dir("src/test/browser"))
        .withPropertyName("adoptionBrowserChecks").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("adoption.native.binary", executable.get().asFile.absolutePath)
    systemProperty("adoption.project", projectDir.absolutePath)
    systemProperty("adoption.node", bml.nodeExecutable.get())
    outputs.upToDateWhen { false }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging.exceptionFormat = TestExceptionFormat.FULL
}

application {
    mainClass.set("bosca.notifications.web.MainKt")
}
tasks.named<JavaExec>("run") {
    dependsOn("bmlBundleClient")
    // `run` IS the dev loop — live reload + no-store CSS/JS. Deployed servers (jar/dist) leave it off.
    systemProperty("bml.dev", System.getProperty("bml.dev") ?: "true")
    // The GraalVM buildtools plugin declares an agent-output dir on every JavaExec, which makes
    // `run` up-to-date-checkable — after any run whose JVM exited 0, Gradle records a success and
    // silently SKIPS starting the server next time. A dev server is never up-to-date.
    outputs.upToDateWhen { false }
}

bml {
    // Workspace dev loop — when this repo is checked out inside bosca-workspace, use the live
    // sibling bml-runtime (bundler + runtime, source-to-source). A standalone clone falls back
    // to the published @bosca/bml package, which the BML plugin resolves from node_modules
    // (declared in package.json).
    val runtimeCheckout = layout.projectDirectory.dir("../../bml/bml-runtime")
    if (runtimeCheckout.asFile.isDirectory) {
        clientBundler.set(runtimeCheckout.file("tools/bundle.mjs"))
        clientRuntime.set(runtimeCheckout.file("src/index.ts"))
        // This site has no authored npm imports; the workspace runtime supplies its toolchain.
        tasks.named("bmlInstallClientDependencies") { onlyIf { !runtimeCheckout.asFile.isDirectory } }
    }
    generateKotlinSources.set(true)
}

// GraphQL data layer — owned by io.bosca.graphql (applied by the BML plugin with BML scalar defaults).
// The site just declares where to fetch from and the generated package; operations live in src/main/graphql.
boscaGraphql {
    endpoint.set(providers.environmentVariable("BML_GRAPHQL_ENDPOINT").orElse("http://localhost:8080/graphql"))
    packageName.set("bosca.notifications.web.graphql")
}

// The deployed artifact is a GraalVM native image (mirrors bosca-server/cli). Build-time flags
// live in src/main/resources/META-INF/native-image/bosca.notifications/notifications-web/
// native-image.properties; classpath resources the binary keeps (notifications.css, logback.xml)
// are in resource-config.json next to it.
graalvmNative {
    testSupport.set(false)
    metadataRepository {
        enabled = true
    }
    binaries {
        named("main") {
            imageName.set("notifications-web")
            mainClass.set("bosca.notifications.web.MainKt")
            buildArgs.add("-O3")
            buildArgs.addAll(providers.gradleProperty("bosca.native.march")
                .map { listOf("-march=$it") }
                .orElse(emptyList()))
            buildArgs.add("-J-Xmx16g")
            buildArgs.add("-J--sun-misc-unsafe-memory-access=allow")
            buildArgs.add("-J--add-opens=java.base/java.nio=ALL-UNNAMED")
            buildArgs.add("-J--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED")
            // kotlin-compiler-embeddable (pulled in via bosca-core's jte-kotlin) embeds
            // org.jline native-image metadata that references a reflection-config.json the
            // jar doesn't contain, aborting the build. Same exclusion bosca-server uses.
            buildArgs.add("--exclude-config")
            buildArgs.add(".*/.*.jar")
            buildArgs.add("^/META-INF/native-image/org\\.jline/.*")
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
                buildArgs.add("--gc=G1")
            }
        }
    }
}

// Component-level aggregate tasks remain available under this project path.
tasks.register("cleanProjects") {
    description = "Run clean (single-project build; matches the workspace aggregator contract)"
    dependsOn(tasks.named("clean"))
}
