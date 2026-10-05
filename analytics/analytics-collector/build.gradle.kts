plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
    id("org.graalvm.buildtools.native")
    alias(libs.plugins.shadow)
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
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

dependencies {
    implementation(project(":bosca-core:core"))
    implementation(project(":analytics:core-analytics"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":bosca-core:core-graalvm"))
    implementation(libs.flyway.postgresql)
    implementation(project(":analytics:analytics")) {
        exclude(group = "io.trino", module = "trino-jdbc")
    }
    implementation(project(":analytics:analytics-server-client"))
    implementation(project(":bosca-core:security"))
    implementation(project(":social:profile"))
    implementation(project(":content:slugs"))
    implementation(project(":bosca-core:storage"))
    implementation(libs.logback.classic)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

ksp {
    arg("ProviderRegistrarPrefix", "AnalyticsCollector")
}

configurations.all {
    exclude(group = "org.jline", module = "jline")
    // Drop netty-all (legacy fat aggregator via Hadoop/Iceberg) so stale 4.1.x natives don't
    // shadow the catalog Netty version; epoll comes in cleanly via :bosca-core:core.
    exclude(group = "io.netty", module = "netty-all")
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

graalvmNative {
    testSupport.set(false)
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
            imageName = "analytics-collector"
            mainClass = "bosca.server.ApplicationKt"
            buildArgs.add("-J-Xmx24g")
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
