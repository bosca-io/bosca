plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("io.bosca.bml")
    id("io.bosca.graphql")
    application
    id("org.graalvm.buildtools.native")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    "boscaGraphqlGenerator"(project(":bosca-graphql:bosca-graphql-client"))
    implementation(project(":bml:core-bml"))
    implementation(project(":bml:bml-annotations"))
    implementation(project(":bml:bml-server"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":bosca-graphql:bosca-graphql-client"))
    implementation(project(":bosca-core:core-graalvm"))
    runtimeOnly(libs.logback.classic)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.test {
    val fixtures = layout.buildDirectory.dir("test-fixtures")
    systemProperty("profilesFixtures", fixtures.get().asFile.absolutePath)
    outputs.dir(fixtures)
}

val typecheckClient by tasks.registering(Exec::class) {
    dependsOn("bmlPrepareClient", "bmlInstallClientDependencies")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), "../../bml/bml-runtime/node_modules/typescript/bin/tsc", "--noEmit")
}

val testClient by tasks.registering(Exec::class) {
    dependsOn("test", "bmlPrepareClient")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), "--test", "src/test/client/components.test.mjs")
}

tasks.named("check") { dependsOn(typecheckClient, testClient) }

application {
    mainClass.set("bosca.profiles.web.MainKt")
}

tasks.named<JavaExec>("run") {
    dependsOn("bmlBundleClient")
    systemProperty("bml.dev", System.getProperty("bml.dev") ?: "true")
    outputs.upToDateWhen { false }
}

bml {
    val runtimeCheckout = layout.projectDirectory.dir("../../bml/bml-runtime")
    if (runtimeCheckout.asFile.isDirectory) {
        clientBundler.set(runtimeCheckout.file("tools/bundle.mjs"))
        clientRuntime.set(runtimeCheckout.file("src/index.ts"))
    }
    generateKotlinSources.set(true)
}

boscaGraphql {
    endpoint.set(providers.environmentVariable("BML_GRAPHQL_ENDPOINT").orElse("http://localhost:8080/graphql"))
    packageName.set("bosca.profiles.web.graphql")
}

graalvmNative {
    testSupport.set(false)
    metadataRepository { enabled = true }
    binaries {
        named("main") {
            imageName.set("profiles-web")
            mainClass.set("bosca.profiles.web.MainKt")
            buildArgs.add("-O3")
            buildArgs.addAll(providers.gradleProperty("bosca.native.march")
                .map { listOf("-march=$it") }
                .orElse(emptyList()))
            buildArgs.add("-J-Xmx16g")
            buildArgs.add("-J--sun-misc-unsafe-memory-access=allow")
            buildArgs.add("-J--add-opens=java.base/java.nio=ALL-UNNAMED")
            buildArgs.add("-J--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED")
            buildArgs.add("--exclude-config")
            buildArgs.add(".*/.*.jar")
            buildArgs.add("^/META-INF/native-image/org\\.jline/.*")
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
                buildArgs.add("--gc=G1")
            }
        }
    }
}

tasks.register("cleanProjects") {
    description = "Run clean (single-project build; matches the workspace aggregator contract)"
    dependsOn(tasks.named("clean"))
}
