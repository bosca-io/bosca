import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
    id("org.graalvm.buildtools.native")
    alias(libs.plugins.shadow)
    application
    id("io.bosca.bml.host")
}

description = "Bosca Atlas backend composition"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    implementation(project(":backup:backup"))
    implementation(project(":admin-support:postgres-admin"))
    implementation(project(":admin-support:nats-admin"))
    implementation(project(":admin-support:meilisearch-admin"))
    implementation(project(":integrations:mux"))
    implementation(project(":integrations:hubspot"))

    implementation(project(":bosca-core:core"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":bosca-core:core-devices"))
    implementation(project(":bosca-core:devices"))
    implementation(project(":bosca-core:security"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":bosca-core:storage"))
    implementation(project(":bosca-core:core-scheduler"))
    implementation(project(":bosca-core:scheduler"))
    implementation(project(":bosca-core:core-configuration"))
    implementation(project(":bosca-core:configuration"))
    implementation(project(":bosca-core:core-forms"))
    implementation(project(":bosca-core:forms"))
    implementation(project(":social:core-profile"))
    implementation(project(":social:profile"))
    implementation(project(":social:collaboration"))
    implementation(project(":social:chat"))
    implementation(project(":content:core-bible-providers"))
    implementation(project(":content:bible-providers"))
    implementation(project(":content:core-content"))
    implementation(project(":content:content"))
    implementation(project(":content:slugs"))
    implementation(project(":content:core-comments"))
    implementation(project(":content:comments"))
    implementation(project(":calendar:core-calendar"))
    implementation(project(":calendar:calendar"))
    implementation(project(":content:core-languages"))
    implementation(project(":content:languages"))
    implementation(project(":content:core-localization"))
    implementation(project(":content:localization"))
    implementation(project(":workops:core-workops"))
    implementation(project(":workops:workops"))
    implementation(project(":workops:store-pipelines"))
    implementation(project(":kubernetes:core-kubernetes"))
    implementation(project(":kubernetes:kubernetes"))
    implementation(project(":kubernetes:kubernetes-pipelines"))
    implementation(project(":scripting:core-scripting"))
    implementation(project(":scripting:scripting"))
    implementation(project(":communications:core-communications"))
    implementation(project(":communications:communications"))
    implementation(project(":analytics:core-analytics"))
    implementation(project(":analytics:analytics"))
    implementation(project(":ai:core-ai"))
    implementation(project(":ai:ai"))
    implementation(project(":ai:kit"))
    implementation(project(":search:core-search"))
    implementation(project(":search:search"))
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":pipelines:pipelines"))
    implementation(project(":git:core-git"))
    implementation(project(":git:core-git-ci"))
    implementation(project(":git:git"))
    implementation(project(":git:git-ci"))
    implementation(project(":git:git-jobs"))
    implementation(project(":artifacts:core-artifacts"))
    implementation(project(":artifacts:artifacts-base"))
    implementation(project(":artifacts:artifacts-git"))
    implementation(project(":artifacts:artifacts-installer"))
    implementation(project(":artifacts:artifacts-admin"))
    implementation(project(":artifacts:artifacts-docker"))
    implementation(project(":artifacts:artifacts-helm"))
    implementation(project(":artifacts:artifacts-maven"))
    implementation(project(":artifacts:artifacts-npm"))
    implementation(project(":artifacts:artifacts-raw"))
    implementation(project(":artifacts:artifacts-ml"))
    implementation(project(":sharedqueue:sharedqueue"))
    implementation(project(":analytics:analytics-server-client"))
    implementation(project(":server:atlas-ui-server"))
    implementation(project(":bosca-core:core-graalvm"))
    implementation(libs.logback.classic)
    implementation(libs.jgit)
    implementation(libs.postgresql)

    ksp(project(":bosca-core:core-ksp"))
    ksp(project(":services-di:service-ksp"))
    ksp(project(":services-di:di-ksp"))

    testImplementation(libs.kotlin.test.junit)
    testImplementation(project(":scripting:scripting-engine"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(project(":bosca-yks:yks"))
    testImplementation(libs.mockk)
    testImplementation(libs.google.androidpublisher)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp)
    testImplementation(libs.greenmail)
    testRuntimeOnly(project(":server:bosca-server"))
    testImplementation(project(":bml:core-bml"))
    testImplementation(project(":bml:bml-server"))
    testImplementation(project(":integrations:meilisearch"))
}

ksp {
    arg("ProviderRegistrarPrefix", "Atlas")
}

application {
    mainClass.set("bosca.atlas.server.ApplicationKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-XX:+UseG1GC")
}

tasks.named<JavaExec>("run") { outputs.upToDateWhen { false } }

// Fresh builds assign a new BML deployment identity without changing the application under test.
normalization.runtimeClasspath { ignore("META-INF/bosca/bml/build-id") }

tasks.test {
    maxParallelForks = providers.gradleProperty("atlas.test.forks").getOrElse("1").toInt()
    for ((variable, inputName) in mapOf(
        "ATLAS_SERVER_TEST_BINARY" to "atlasServerTestBinary",
        "ATLAS_UI_TEST_BINARY" to "atlasUiTestBinary",
        "BOSCA_SERVER_TEST_BINARY" to "boscaServerTestBinary",
    )) {
        val executable = providers.environmentVariable(variable)
        inputs.property("${inputName}Path", executable.orElse(""))
        executable.orNull?.let { path ->
            inputs.file(path).withPropertyName(inputName).withPathSensitivity(PathSensitivity.NONE)
        }
    }
    val serverClasspath = sourceSets.main.get().runtimeClasspath
    val testClasspath = sourceSets.test.get().runtimeClasspath
    val workspaceRoot = rootProject.projectDir.absolutePath
    doFirst {
        systemProperty("atlasServerClasspath", serverClasspath.asPath)
        systemProperty("atlasTestClasspath", testClasspath.asPath)
        systemProperty("workspaceRoot", workspaceRoot)
    }
    inputs.files(listOf("git/git-ui", "artifacts/artifacts-ui", "admin-support/system-ui").map { path ->
        rootProject.fileTree("$path/src/main/graphql") { include("*.graphql") }
    }).withPropertyName("uiGraphqlOperations").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("web/packages/auth/src/graphql.ts"))
        .withPropertyName("browserAuthOperations").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("testPlatform", "${System.getProperty("os.name")}/${System.getProperty("os.arch")}")
    val projectDirectory = layout.projectDirectory
    listOf("ATLAS_SERVER_TEST_BINARY", "ATLAS_UI_TEST_BINARY").forEach { variable ->
        inputs.files(providers.environmentVariable(variable).map { executable ->
            if (executable.isBlank()) emptyList() else listOf(projectDirectory.file(executable))
        }.orElse(emptyList())).withPropertyName(variable).withPathSensitivity(PathSensitivity.NONE)
    }
    systemProperty("atlas.postgres.browser", providers.gradleProperty("atlas.postgres.browser").getOrElse("false"))
    systemProperty("atlas.nats.browser", providers.gradleProperty("atlas.nats.browser").getOrElse("false"))
    systemProperty("atlas.meilisearch.browser", providers.gradleProperty("atlas.meilisearch.browser").getOrElse("false"))
    inputs.file(rootProject.file("admin-support/system-ui/src/test/browser/meilisearch.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("atlas.appearance.browser", providers.gradleProperty("atlas.appearance.browser").getOrElse("false"))
    inputs.file(rootProject.file("admin-support/system-ui/src/test/browser/appearance.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("admin-support/system-ui/src/test/browser/nats.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("admin-support/system-ui/src/test/browser/postgres.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("atlas.hubspot.browser", providers.gradleProperty("atlas.hubspot.browser").getOrElse("false"))
    inputs.file(rootProject.file("admin-support/system-ui/src/test/browser/hubspot.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("atlas.operations.browser", providers.gradleProperty("atlas.operations.browser").getOrElse("false"))
    inputs.file(rootProject.file("admin-support/system-ui/src/test/browser/operations.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("atlas.bible.browser", providers.gradleProperty("atlas.bible.browser").getOrElse("false"))
    inputs.file(rootProject.file("admin-support/system-ui/src/test/browser/bible.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("atlas.federation.browser", providers.gradleProperty("atlas.federation.browser").getOrElse("false"))
    inputs.file(rootProject.file("admin-support/system-ui/src/test/browser/federation.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("atlas.providers.browser", providers.gradleProperty("atlas.providers.browser").getOrElse("false"))
    inputs.file(rootProject.file("admin-support/system-ui/src/test/browser/providers.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("atlas.email.browser", providers.gradleProperty("atlas.email.browser").getOrElse("false"))
    inputs.file(rootProject.file("admin-support/system-ui/src/test/browser/email.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("atlas.backups.browser", providers.gradleProperty("atlas.backups.browser").getOrElse("false"))
    inputs.file(rootProject.file("admin-support/system-ui/src/test/browser/backups.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("atlas.passkeys.browser", providers.gradleProperty("atlas.passkeys.browser").getOrElse("false"))
    inputs.file(rootProject.file("admin-support/system-ui/src/test/browser/passkeys.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("atlas.security.browser", providers.gradleProperty("atlas.security.browser").getOrElse("false"))
    inputs.file(rootProject.file("admin-support/system-ui/src/test/browser/security.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("atlas.artifacts.browser", providers.gradleProperty("atlas.artifacts.browser").getOrElse("false"))
    systemProperty("atlas.workops.browser", providers.gradleProperty("atlas.workops.browser").getOrElse("false"))
    systemProperty("atlas.workops.browser.scripts", providers.gradleProperty("atlas.workops.browser.scripts").getOrElse(""))
    systemProperty("atlas.workops.coverage.agent", providers.gradleProperty("atlas.workops.coverage.agent").getOrElse(""))
    systemProperty("atlas.workops.coverage.directory", providers.gradleProperty("atlas.workops.coverage.directory").getOrElse(""))
    val workOpsCutover = providers.gradleProperty("atlas.workops.cutover").getOrElse("false")
    systemProperty("atlas.workops.cutover", workOpsCutover)
    if (workOpsCutover == "true") {
        systemProperty("atlas.native.artifacts", layout.buildDirectory.dir("reports/workops-cutover/processes").get().asFile.absolutePath)
        inputs.files(rootProject.fileTree("web") {
            include("projects/studio/app/**", "projects/studio/server/**", "projects/studio/nuxt.config.ts",
                "projects/studio/package.json", "packages/*/src/**", "packages/*/package.json",
                "pnpm-lock.yaml", "pnpm-workspace.yaml", "package.json")
        }).withPropertyName("studioCutoverSources").withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(rootProject.file("deployments/local/Caddyfile")).withPathSensitivity(PathSensitivity.RELATIVE)
    }
    inputs.dir(rootProject.file("workops/workops-ui/src/test/browser"))
        .withPropertyName("workOpsBrowserScripts").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("workops/workops-ui/src/test/client/collaboration-large.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("artifacts/artifacts-ui/src/test/browser/artifacts.mjs"))
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    outputs.doNotCacheIf("Uses the locally installed browser") { task ->
        (task as Test).systemProperties.any { (name, value) ->
            name.startsWith("atlas.") && name.endsWith(".browser") && value == "true"
        }
    }
}

tasks.register<Test>("nativeHostTest") {
    group = "verification"
    description = "Exercise native Atlas API, registry, workers, and BML hosts with isolated infrastructure."
    dependsOn(tasks.testClasses, "nativeCompile", ":server:atlas-ui-server:nativeCompile")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("bosca.atlas.server.Atlas*IntegrationTest")
        // These two tests require JVM fixture entry points with intercepted third-party clients.
        excludeTestsMatching("bosca.atlas.server.AtlasBibleProvidersIntegrationTest")
        excludeTestsMatching("bosca.atlas.server.AtlasHubSpotIntegrationTest")
    }
    val extension = if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else ""
    val backend = layout.buildDirectory.file("native/nativeCompile/bosca-atlas-server$extension")
    val ui = project(":server:atlas-ui-server").layout.buildDirectory.file("native/nativeCompile/bosca-atlas-ui-server$extension")
    inputs.file(backend).withPropertyName("nativeBackend").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(ui).withPropertyName("nativeUi").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(fileTree(layout.buildDirectory.dir("native/nativeCompile")) {
        include("*.so", "*.so.*", "*.dylib", "*.dll", "*.jnilib")
    }).withPropertyName("nativeBackendLibraries").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(fileTree(project(":server:atlas-ui-server").layout.buildDirectory.dir("native/nativeCompile")) {
        include("*.so", "*.so.*", "*.dylib", "*.dll", "*.jnilib")
    }).withPropertyName("nativeUiLibraries").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(rootProject.file("artifacts/artifacts-ui/src/test/browser/artifacts.mjs"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    environment("ATLAS_SERVER_TEST_BINARY", backend.get().asFile.absolutePath)
    environment("ATLAS_UI_TEST_BINARY", ui.get().asFile.absolutePath)
    systemProperty("workspaceRoot", rootProject.projectDir.absolutePath)
    systemProperty("atlas.artifacts.browser", "true")
    doFirst { systemProperty("atlasTestClasspath", sourceSets.test.get().runtimeClasspath.asPath) }
    val platformBinary = providers.environmentVariable("BOSCA_SERVER_TEST_BINARY")
    inputs.property("boscaServerTestBinaryPath", platformBinary.orElse(""))
    platformBinary.orNull?.let { path ->
        inputs.file(path).withPropertyName("boscaServerTestBinary").withPathSensitivity(PathSensitivity.NONE)
    }
    systemProperty("atlas.workops.browser", providers.gradleProperty("atlas.workops.browser").getOrElse("false"))
    systemProperty("atlas.workops.browser.scripts", providers.gradleProperty("atlas.workops.browser.scripts").getOrElse(""))
    inputs.file(rootProject.file("workops/workops-ui/src/test/client/collaboration-large.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    val workOpsCutover = providers.gradleProperty("atlas.workops.cutover").getOrElse("false")
    systemProperty("atlas.workops.cutover", workOpsCutover)
    if (workOpsCutover == "true") {
        inputs.files(rootProject.fileTree("web") {
            include("projects/studio/app/**", "projects/studio/server/**", "projects/studio/nuxt.config.ts",
                "projects/studio/package.json", "packages/*/src/**", "packages/*/package.json",
                "pnpm-lock.yaml", "pnpm-workspace.yaml", "package.json")
        }).withPropertyName("studioCutoverSources").withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(rootProject.file("deployments/local/Caddyfile")).withPathSensitivity(PathSensitivity.RELATIVE)
    }
    systemProperty("atlas.native.artifacts", layout.buildDirectory.dir("reports/native-host").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}

configurations.all {
    exclude(group = "org.jline", module = "jline")
    exclude(group = "io.netty", module = "netty-all")
}

tasks.shadowJar {
    // The packaged host includes the deployment identities from its dependency archives.
    inputs.files(includedDependencies).withPropertyName("packagedDependencyArchives")
        .withPathSensitivity(PathSensitivity.NONE)
    isZip64 = true
    filesMatching(listOf("META-INF/services/**", "META-INF/bosca/bml/build-id")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
    append("META-INF/bosca/bml/build-id")
}

graalvmNative {
    testSupport.set(false)
    agent { enabled.set(false) }
    metadataRepository { enabled = true }
    binaries {
        named("main") {
            imageName.set("bosca-atlas-server")
            mainClass.set("bosca.atlas.server.ApplicationKt")
            buildArgs.add("-O3")
            // The JDK build-time timezone registry retains this provider and its constant value graph.
            buildArgs.add("--initialize-at-build-time=" + listOf(
                "net.fortuna.ical4j.model.CalendarDateFormat",
                "net.fortuna.ical4j.model.CalendarDateFormat\$InstantTemporalQuery",
                "net.fortuna.ical4j.model.CalendarDateFormat\$LocalDateTemporalQuery",
                "net.fortuna.ical4j.model.CalendarDateFormat\$LocalDateTimeTemporalQuery",
                "net.fortuna.ical4j.model.CalendarDateFormat\$OffsetDateTimeTemporalQuery",
                "net.fortuna.ical4j.model.ComponentList",
                "net.fortuna.ical4j.model.DefaultZoneRulesProvider",
                "net.fortuna.ical4j.model.ParameterList",
                "net.fortuna.ical4j.model.PropertyList",
                "net.fortuna.ical4j.model.TemporalAdapter",
                "net.fortuna.ical4j.model.TimeZoneLoader",
                "net.fortuna.ical4j.model.TimeZoneRegistry",
                "net.fortuna.ical4j.model.TimeZoneUpdater",
                "net.fortuna.ical4j.model.ZoneOffsetAdapter",
                "net.fortuna.ical4j.model.component.Standard",
                "net.fortuna.ical4j.model.parameter.Value",
                "net.fortuna.ical4j.model.property.DtStart",
                "net.fortuna.ical4j.model.property.TzOffsetFrom",
                "net.fortuna.ical4j.model.property.TzOffsetTo",
                "net.fortuna.ical4j.util.MapTimeZoneCache",
            ).joinToString(","))
            buildArgs.add("-Djava.awt.headless=true")
            buildArgs.add("-H:+IncludeAllLocales")
            buildArgs.add("-H:+GenerateEmbeddedResourcesFile")
            buildArgs.addAll(providers.gradleProperty("bosca.native.march").map { listOf("-march=$it") }.orElse(emptyList()))
            providers.gradleProperty("bosca.native.buildHeap").orNull?.let { jvmArgs("-Xmx$it") }
            buildArgs.addAll(providers.gradleProperty("bosca.native.parallelism").map { listOf("--parallelism=$it") }.orElse(emptyList()))
            buildArgs.addAll(listOf("--exclude-config", ".*/.*.jar", "^/META-INF/native-image/org[.]jline/.*"))
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) buildArgs.add("--gc=G1")
        }
    }
}

tasks.register<Zip>("nativeDistZip") {
    group = "distribution"
    description = "Package the native Atlas backend and its generated runtime libraries."
    dependsOn("nativeCompile")
    archiveFileName.set("atlas-server-native.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(tasks.named<BuildNativeImageTask>("nativeCompile").flatMap { it.outputDirectory }) {
        include("bosca-atlas-server", "bosca-atlas-server.exe", "*.so", "*.so.*", "*.dylib", "*.dll", "*.jnilib")
        filesMatching(listOf("bosca-atlas-server", "bosca-atlas-server.exe")) {
            permissions { unix("755") }
        }
    }
}
