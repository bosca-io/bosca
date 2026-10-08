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
    mainClass = "bosca.kubernetes.controller.ApplicationKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-XX:+UseG1GC")
}

val projects = arrayOf(
    project(":bosca-core:core"),
    project(":bosca-core:core-security"),
    project(":bosca-core:security"),
    // SecurityProviderRegistrar (loaded in Application.kt for the auth module) hard-references
    // core-profile contracts: SecurityServiceImpl now takes ObjectProvider<AttributeVerificationService>,
    // and KSP bakes that parameter type (plus VerifiableAttributeType) into the generated registrar.
    // Without core-profile on the classpath, register() fails with NoClassDefFoundError at startup. The
    // dependency stays lazy at runtime (this binary never resolves the profile services), so only the
    // pure-contracts core-profile is needed here, not the profile impl.
    project(":social:core-profile"),
    project(":bosca-core:core-graalvm"),
    project(":analytics:analytics-server-client"),
    project(":kubernetes:core-kubernetes"),
    project(":kubernetes:kubernetes"),
)

configurations.all {
    exclude(group = "org.jline", module = "jline")
    // Drop netty-all (legacy fat aggregator via Hadoop/Iceberg) so stale 4.1.x natives don't
    // shadow the catalog Netty version; epoll comes in cleanly via :bosca-core:core.
    exclude(group = "io.netty", module = "netty-all")
    // fabric8's default HTTP client (vertx) is hostile to native-image — Vert.x's
    // static initializers touch netty internals that fail at build time. We use the
    // OkHttp implementation instead (added below), which is mature under GraalVM.
    exclude(group = "io.fabric8", module = "kubernetes-httpclient-vertx")
}

dependencies {
    projects.forEach { implementation(it) }

    implementation(project(":sharedqueue:sharedqueue"))
    implementation(libs.logback.classic)

    compileOnly(libs.graalvm.nativeimage)

    implementation(platform(libs.fabric8.kubernetes.client.bom))
    implementation(libs.fabric8.kubernetes.client)
    implementation(libs.fabric8.kubernetes.httpclient.okhttp)
    implementation(libs.fabric8.kubernetes.model.gatewayapi)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.commons.compress)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

ksp {
    arg("ProviderRegistrarPrefix", "KubernetesController")
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
            "io.grpc:grpc-netty-shaded",
        ))
    }
    binaries {
        named("main") {
            imageName = "bosca-kubernetes-controller"
            mainClass = "bosca.kubernetes.controller.ApplicationKt"
            buildArgs.add("-O3")
            buildArgs.addAll(providers.gradleProperty("bosca.native.march")
                .map { listOf("-march=$it") }
                .orElse(emptyList()))
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
