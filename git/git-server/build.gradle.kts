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

application {
    mainClass = "bosca.git.server.ApplicationKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-XX:+UseG1GC")
}

val projects = arrayOf(
    project(":bosca-core:core"),
    project(":pipelines:core-pipelines"),
    project(":git:core-git"),
    project(":git:core-git-ci"),
    project(":bosca-core:core-security"),
    project(":social:core-profile"),
    project(":social:profile"),
    project(":bosca-core:security"),
    project(":bosca-core:core-scheduler"),
    project(":bosca-core:scheduler"),
    project(":pipelines:pipelines"),
    project(":bosca-core:core-storage"),
    project(":bosca-core:storage"),
    project(":content:core-content"),
    project(":search:core-search"),
    project(":search:search"),
    project(":content:slugs"),
    project(":analytics:analytics-server-client"),
    project(":bosca-core:core-graalvm"),
    project(":git:git"),
    project(":git:git-ci"),
    project(":git:git-jobs"),
)

configurations.all {
    exclude(group = "org.jline", module = "jline")
    // Drop netty-all (legacy fat aggregator via Hadoop/Iceberg) so stale 4.1.x natives don't
    // shadow the catalog Netty version; epoll comes in cleanly via :bosca-core:core.
    exclude(group = "io.netty", module = "netty-all")
}

dependencies {
    projects.forEach { implementation(it) }

    implementation(project(":sharedqueue:sharedqueue"))
    implementation(libs.jgit)
    implementation(libs.logback.classic)
    api(libs.postgresql)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

ksp {
    arg("ProviderRegistrarPrefix", "GitServer")
}

tasks.shadowJar {
    isZip64 = true
    // Service descriptors must reach the transformer before duplicate filtering.
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
}

afterEvaluate {
    configurations.matching { it.name.startsWith("nativeImageTest") }.configureEach {
        setExtendsFrom(emptyList())
    }
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
            imageName = "bosca-git-server"
            mainClass = "bosca.git.server.ApplicationKt"
            buildArgs.add("--exclude-config")
            buildArgs.add(".*/.*.jar")
            buildArgs.add("^/META-INF/native-image/org\\.jline/.*")
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
                buildArgs.add("--gc=G1")
            }
        }
    }
}
