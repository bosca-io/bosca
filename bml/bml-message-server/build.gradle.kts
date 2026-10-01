plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
    application
    id("org.graalvm.buildtools.native") // GraalVM 25i native image with Crema runtime class loading
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// The BML Message Server: a lean bosca-core Netty composition
// root — bml-server's cousin — that hosts published message-project jars via bml-message-host.
// It fetches digest-verified jars from the raw artifacts registry, hot-reloads on the registry's
// ArtifactVersionPublished event (poll backstop), renders over a PRIVATE HTTP API, and serves
// each version's bundled assets at version-pinned PUBLIC URLs (the only public surface).
dependencies {
    implementation(project(":bml:core-bml"))
    implementation(project(":bml:bml-message-host"))
    implementation(project(":bml:bml-message-client")) // the render API's wire DTOs (single source)
    implementation(project(":bosca-core:core"))             // BoscaApplication / Router / Netty engine / PubSub
    implementation(project(":artifacts:core-artifacts"))   // ArtifactVersionPublished (the reload signal)
    implementation(project(":analytics:analytics-server-client")) // engagement events -> the analytics pipeline (CTR)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)                 // artifact fetch (ArtifactsRegistryClient precedent)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    // Route tests host REAL message jars: .bml -> bml-compiler codegen -> embedded kotlinc -> jar.
    testImplementation(project(":bml:bml-compiler"))
    testImplementation(project(":bml:bml-annotations"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
    testImplementation(libs.testcontainers) // real-NATS e2e for the publish-event reload path
    testImplementation(project(":bosca-core:test-support"))
}

application {
    mainClass.set("bosca.bml.message.server.MainKt")
}

tasks.register<JavaExec>("packageDefaultMessageProject") {
    group = "distribution"
    description = "Downloads the latest bosca-messages jar into the service image fallback layout"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("bosca.bml.message.server.PackageBundledMessageProjectKt")
    val output = layout.buildDirectory.dir("bundled-message-projects")
    environment("BML_MESSAGE_BUNDLED_PROJECTS_DIR", output.get().asFile.absolutePath)
    outputs.dir(output)
    // Latest is registry state, not an input Gradle can fingerprint.
    outputs.upToDateWhen { false }
}

// Native image via GraalVM 25i's Crema (runtime class loading): the server hot-loads message jars
// inside the native binary. Build with JAVA_HOME + GRAALVM_HOME at the 25i toolchain (the GDS
// `25i1` stream — sdkman's 25.0.3-graal lacks `GraalJITCompileAtRuntime`; `./gradlew --stop`
// after switching, GRAALVM_HOME is not part of the daemon fingerprint). KNOWN LIMITATION
// (accepted): retired classloader generations never unload natively (~230KB leaked per publish;
// Crema scopes load/link/execute only) — routine restarts cover it at email publish cadence.
graalvmNative {
    binaries {
        named("main") {
            imageName.set("bml-message-server")
            buildArgs.addAll(
                "--enable-url-protocols=jar",
                "-J--sun-misc-unsafe-memory-access=allow",
                "-H:+UnlockExperimentalVMOptions",
                "-H:+RuntimeClassLoading",
                "-H:+GraalJITCompileAtRuntime",
                // Locale data for localized rendering: native images bundle only the
                // default locale otherwise, and t()/format* degrade SILENTLY to root-locale
                // output. ALL locales, deliberately: the localization project defines the
                // platform's locales as runtime data, so an enumerated build-time list here
                // reintroduces the silent degradation for any language added after the build.
                // Costs binary size once; never costs a mystery formatting bug.
                "-H:+IncludeAllLocales",
                // kotlin-compiler-embeddable (via bosca-core's jte-kotlin) embeds org.jline
                // metadata referencing a reflection-config.json the jar lacks (bosca-server's fix).
                "--exclude-config", ".*/.*.jar", "^/META-INF/native-image/org\\.jline/.*",
            )
            // The Crema contract: everything runtime-loaded template code links against must be
            // preserved in the image — the shared render surface + the libraries message projects
            // are allowed to depend on (core-bml, GraphQL client, kotlin-stdlib, coroutines,
            // serialization).
            buildArgs.add(project.provider {
                val wanted = listOf(
                    "kotlin-stdlib-2", "kotlinx-coroutines-core-jvm",
                    "kotlinx-serialization-json-jvm", "kotlinx-serialization-core-jvm",
                    "core-bml", "bml-message-host", "bosca-graphql-client",
                )
                val classpath = project.extensions.getByType(SourceSetContainer::class.java)
                    .getByName("main").runtimeClasspath.files
                val paths = wanted.map { pattern ->
                    classpath.firstOrNull { it.name.contains(pattern) }?.absolutePath
                        ?: error("Required Crema preserve classpath entry not found: $pattern")
                }
                "-H:Preserve=module=java.base," + paths.joinToString(",") { "path=$it" }
            })
        }
    }
}

kover {
    currentProject {
        instrumentation {
            // Runtime-loaded generated classes (from test-built jars) must stay uninstrumented —
            // Kover's agent pins every class it instruments (same as bml-message-host).
            excludedClasses.addAll("bml.generated.*")
        }
    }
}
