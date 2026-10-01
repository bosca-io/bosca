plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
    id("org.graalvm.buildtools.native")
    alias(libs.plugins.shadow)
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

val scriptingEngineEnabled = findProperty("bosca.scripting.engine")?.toString()?.toBoolean() ?: true

if (!scriptingEngineEnabled) {
    println("****** Scripting engine is disabled. ******")
} else {
    println("****** Scripting engine is enabled. ******")
}

// Pick the wrapper that provides ScriptingEngineRegistrarProvider. The enabled
// variant returns the KSP-generated ScriptingEngineProviderRegistrar (compiles
// only when scripting-engine is on the classpath); the disabled variant returns
// null so Application.kt skips the registration and falls back to RemoteEngine.
sourceSets.named("main") {
    val variant = if (scriptingEngineEnabled) "scriptingEnabled" else "scriptingDisabled"
    java.srcDir("src/$variant/kotlin")
}

application {
    mainClass = "bosca.server.ApplicationKt"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-XX:+UseG1GC",
    )
}

val projects = buildList {
    add(project(":bosca-core:core"))
    add(project(":content:core-content"))
    add(project(":bosca-core:core-security"))
    add(project(":social:core-profile"))
    add(project(":communications:core-communications"))
    add(project(":social:core-chat"))
    add(project(":social:core-collaboration"))
    add(project(":social:core-community"))
    add(project(":search:core-search"))
    add(project(":content:core-languages"))
    add(project(":analytics:core-analytics"))
    add(project(":bosca-core:core-configuration"))
    add(project(":bosca-core:core-storage"))
    add(project(":bosca-core:core-scheduler"))
    add(project(":bosca-core:core-events"))
    add(project(":pipelines:core-pipelines"))
    add(project(":ecommerce:core-ecommerce"))
    add(project(":ecommerce:ecommerce"))
    add(project(":feeds:core-feeds"))
    add(project(":feeds:feeds"))
    add(project(":scripting:core-scripting"))
    add(project(":content:core-localization"))
    add(project(":git:core-git"))
    add(project(":git:core-git-ci"))
    add(project(":ai:core-ai"))
    add(project(":ai:ai"))
    add(project(":git:git"))
    add(project(":git:git-ci"))
    add(project(":git:git-jobs"))
    add(project(":social:profile"))
    add(project(":bosca-core:security"))
    add(project(":content:slugs"))
    add(project(":analytics:analytics"))
    add(project(":analytics:analytics-ai"))
    add(project(":content:content"))
    add(project(":communications:communications"))
    add(project(":social:chat"))
    add(project(":social:collaboration"))
    add(project(":social:community"))
    add(project(":search:search"))
    add(project(":bosca-core:storage"))
    add(project(":bosca-core:scheduler"))
    add(project(":bosca-core:events"))
    add(project(":pipelines:pipelines"))
    add(project(":content:languages"))
    add(project(":bosca-core:configuration"))
    add(project(":scripting:scripting"))
    if (scriptingEngineEnabled) {
        add(project(":scripting:scripting-engine"))
    }
    add(project(":content:localization"))
    add(project(":bosca-core:core-forms"))
    add(project(":experimentation:core-segmentation"))
    add(project(":experimentation:core-recommendations"))
    add(project(":experimentation:core-experimentation"))
    add(project(":bosca-core:core-devices"))
    add(project(":calendar:core-calendar"))
    add(project(":bosca-core:forms"))
    add(project(":experimentation:segmentation"))
    add(project(":experimentation:recommendations"))
    add(project(":experimentation:experimentation"))
    add(project(":content:comments"))
    add(project(":workops:core-workops"))
    add(project(":workops:workops"))
    add(project(":workops:store-pipelines"))
    add(project(":workops:workops-jobs"))
    add(project(":gateway:core-gateway"))
    add(project(":gateway:gateway"))
    add(project(":kubernetes:core-kubernetes"))
    add(project(":kubernetes:kubernetes"))
    add(project(":kubernetes:kubernetes-pipelines"))
    add(project(":bosca-core:devices"))
    add(project(":calendar:calendar"))
    add(project(":content:bible-compiler"))
    add(project(":content:docs"))
    add(project(":backup:backup"))
    add(project(":admin-support:meilisearch-admin"))
    add(project(":admin-support:nats-admin"))
    add(project(":admin-support:postgres-admin"))
    add(project(":artifacts:core-artifacts"))
    add(project(":artifacts:artifacts-base"))
    add(project(":artifacts:artifacts-admin"))
    add(project(":artifacts:artifacts-ml"))

    add(libs.flyway.postgresql)

    add(project(":sharedqueue:sharedqueue"))
    add(project(":admin-support:diagnostics"))
    add(project(":analytics:analytics-server-client"))

    add(project(":integrations:hubspot"))
    add(project(":integrations:meilisearch"))
    add(project(":integrations:mux"))

    add(project(":ai:kit"))
}

val internalProjects = arrayOf(
    project(":server:messages-pages"),
)

configurations.all {
    exclude(group = "org.jline", module = "jline")
    // Legacy fat aggregator (via Hadoop/Iceberg in :analytics) that drags in stale 4.1.x
    // native modules at versions that don't match the rest of Netty. epoll comes in cleanly
    // at the catalog version via :bosca-core:core instead.
    exclude(group = "io.netty", module = "netty-all")
    if (!scriptingEngineEnabled) {
        exclude(group = "io.bosca", module = "scripting-engine")
    }
}

dependencies {
    projects.forEach { implementation(it) }
    internalProjects.forEach { implementation(it) }

    implementation(project(":bosca-core:core-graalvm"))
    implementation(libs.logback.classic)

    api(libs.postgresql)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))

    projects.forEach { kover(it) }

    kover(project(":bosca-core:core-ksp"))
    kover(project(":services-di:service-ksp"))
    kover(project(":services-di:di-ksp"))

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

ksp {
    arg("ProviderRegistrarPrefix", "Bosca")
}

tasks.shadowJar {
    isZip64 = true
    mergeServiceFiles()
}

afterEvaluate {
    configurations.matching { it.name.startsWith("nativeImageTest") }.configureEach {
        setExtendsFrom(emptyList())
    }
}

tasks.named<JavaExec>("run") {
    outputs.upToDateWhen { false }
}

graalvmNative {
    testSupport.set(false)
    agent {
        enabled.set(false)
        metadataCopy {
            outputDirectories.add("src/main/resources/META-INF/native-image")
            mergeWithExisting.set(true)
        }
    }
    metadataRepository {
        enabled = true
        excludedModules.addAll(listOf(
            "net.bytebuddy:byte-buddy",
            "net.bytebuddy:byte-buddy-agent",
            "io.mockk:mockk",
            "io.mockk:mockk-jvm",
            "org.objenesis:objenesis",
            "org.junit.jupiter:junit-jupiter-api",
            "org.junit.platform:junit-platform-launcher",
            "org.jetbrains.kotlin:kotlin-test",
            "org.jetbrains.kotlin:kotlin-test-junit",
            "org.conscrypt:conscrypt-openjdk-uber",
            "io.grpc:grpc-netty-shaded"
        ))
    }
    binaries {
        named("main") {
            imageName = "bosca-server"
            mainClass = "bosca.server.ApplicationKt"
            buildArgs.add("-J-Xmx36g")
            buildArgs.add("-J--sun-misc-unsafe-memory-access=allow")
            buildArgs.add("-J--add-opens=java.base/java.nio=ALL-UNNAMED")
            buildArgs.add("-J--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED")
            if (scriptingEngineEnabled) {
                buildArgs.add("--exclude-config")
                buildArgs.add(".*/kotlin-compiler-embeddable-.*.jar")
                buildArgs.add("^/META-INF/native-image/org\\.jline/.*")
            } else {
                buildArgs.add("--exclude-config")
                buildArgs.add(".*/.*.jar")
                buildArgs.add("^/META-INF/native-image/org\\.jline/.*")
            }
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
                buildArgs.add("--gc=G1")
            }
        }
    }
}
