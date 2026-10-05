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
    project(":content:core-content"),
    project(":bosca-core:core-security"),
    project(":social:core-profile"),
    project(":communications:core-communications"),
    project(":social:core-community"),
    project(":social:core-chat"),
    project(":social:core-collaboration"),
    project(":search:core-search"),
    project(":content:core-languages"),
    project(":analytics:core-analytics"),
    project(":bosca-core:core-configuration"),
    project(":bosca-core:core-storage"),
    project(":bosca-core:core-scheduler"),
    project(":git:core-git"),
    project(":git:core-git-ci"),
    project(":workops:core-workops"),
    project(":artifacts:core-artifacts"),
    project(":git:git"),
    project(":git:git-ci"),
    project(":git:git-jobs"),
    project(":workops:workops"),
    project(":workops:workops-jobs"),
    // Artifacts registry impl — the git-ci requirement checker (which runs in this runner) resolves
    // the workops RequiredArtifactVerifier, whose factory needs ArtifactRepositoryService from
    // artifacts-base. workops only depends on core-artifacts (contracts), so the impl must be
    // declared here explicitly, mirroring bosca-server.
    project(":artifacts:artifacts-base"),
    project(":workops:store-pipelines"),
    project(":kubernetes:core-kubernetes"),
    project(":kubernetes:kubernetes"),
    project(":kubernetes:kubernetes-pipelines"),
    project(":scripting:core-scripting"),
    project(":pipelines:core-pipelines"),
    project(":pipelines:pipelines"),
    project(":ecommerce:core-ecommerce"),
    project(":ecommerce:ecommerce"),
    project(":feeds:core-feeds"),
    project(":feeds:feeds"),
    project(":ai:core-ai"),
    project(":ai:ai"),
    project(":social:profile"),
    project(":bosca-core:security"),
    project(":content:slugs"),
    project(":analytics:analytics"),
    project(":analytics:analytics-ai"),
    project(":content:content"),
    project(":content:comments"),
    project(":communications:communications"),
    project(":social:community"),
    project(":social:chat"),
    project(":social:collaboration"),
    project(":search:search"),
    project(":bosca-core:storage"),
    project(":bosca-core:scheduler"),
    project(":content:languages"),
    project(":bosca-core:configuration"),
    project(":scripting:scripting"),
    project(":scripting:scripting-engine"),
    project(":bosca-core:core-forms"),
    project(":experimentation:core-segmentation"),
    project(":experimentation:core-recommendations"),
    project(":experimentation:core-experimentation"),
    project(":bosca-core:core-devices"),
    project(":bosca-core:forms"),
    project(":experimentation:segmentation"),
    project(":experimentation:recommendations"),
    project(":experimentation:experimentation"),
    project(":bosca-core:devices"),
    project(":content:bible-compiler"),
    project(":backup:backup"),
    project(":admin-support:meilisearch-admin"),
    project(":admin-support:nats-admin"),
    project(":admin-support:postgres-admin"),

    project(":sharedqueue:sharedqueue"),
    project(":admin-support:diagnostics"),
    project(":analytics:analytics-server-client"),

    project(":integrations:hubspot"),
    project(":integrations:meilisearch"),
    project(":integrations:mux"),

    project(":ai:kit")
)

configurations.all {
    exclude(group = "org.jline", module = "jline")
    // Legacy fat aggregator (via Hadoop/Iceberg in :analytics) that drags in stale 4.1.x
    // native modules, including a mismatched kqueue that crashes Lettuce on macOS.
    exclude(group = "io.netty", module = "netty-all")
}

dependencies {
    projects.forEach { implementation(it) }

    implementation(libs.logback.classic)

    api(libs.postgresql)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)

    projects.forEach { kover(it) }

    kover(project(":bosca-core:core-ksp"))
    kover(project(":services-di:service-ksp"))
    kover(project(":services-di:di-ksp"))

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

ksp {
    arg("ProviderRegistrarPrefix", "BoscaRunner")
}

tasks.shadowJar {
    isZip64 = true
    mergeServiceFiles()
}
