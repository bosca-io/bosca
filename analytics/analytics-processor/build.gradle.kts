plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
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

val projects = arrayOf(
    project(":bosca-core:core"),
    project(":analytics:core-analytics"),
    project(":bosca-core:core-security"),
    project(":scripting:core-scripting"),
    project(":bosca-core:core-scheduler"),
    project(":bosca-core:core-configuration"),
    project(":bosca-core:core-storage"),
    project(":analytics:analytics"),
    project(":analytics:analytics-ai"),
    project(":bosca-core:security"),
    project(":social:profile"),
    project(":content:slugs"),
    libs.flyway.postgresql,
    project(":scripting:scripting"),
    project(":scripting:scripting-engine"),
    project(":pipelines:core-pipelines"),
    project(":pipelines:pipelines"),
    project(":bosca-core:scheduler"),
    project(":bosca-core:configuration"),
    project(":bosca-core:storage"),
    project(":ai:ai"),
    project(":sharedqueue:sharedqueue"),
    project(":analytics:analytics-server-client"),
)

configurations.all {
    exclude(group = "org.jline", module = "jline")
}

dependencies {
    projects.forEach { implementation(it) }

    implementation(libs.logback.classic)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(project(":bosca-core:test-support"))

    projects.filterIsInstance<ProjectDependency>().forEach { kover(it) }

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

ksp {
    arg("ProviderRegistrarPrefix", "AnalyticsProcessor")
}

tasks.shadowJar {
    isZip64 = true
    mergeServiceFiles()
}
