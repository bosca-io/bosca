// Bosca transactional communications — a BML message project. Each `.bml` under
// src/main/bml/messages/ is a `<message>` unit with email and/or push channels, compiled into the
// generated `bml.generated.BmlMessages` registry. The pages/ are the local preview site.
//
//     ./gradlew run        # -> http://localhost:4567/
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("io.bosca.bml") // `.bml` render codegen (pages + email templates)
    id("io.bosca.graphql") // typed GraphQL data access for render-time source content
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    "boscaGraphqlGenerator"(project(":bosca-graphql:bosca-graphql-client"))
    implementation(project(":bml:core-bml"))          // message contracts + render runtime
    implementation(project(":bml:bml-annotations"))   // @BmlMessage / @BmlPage / @BmlGenerated
    implementation(project(":bml:bml-server"))        // BmlServer — the preview site
    implementation(project(":bosca-graphql:bosca-graphql-client"))    // generated typed GraphQL operations
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json) // @Serializable email payload models
    runtimeOnly(libs.logback.classic)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

application {
    mainClass.set("bosca.messages.MainKt")
}
tasks.named<JavaExec>("run") {
    // `run` IS the dev loop — live reload on the preview pages. Deployed hosts leave it off.
    systemProperty("bml.dev", System.getProperty("bml.dev") ?: "true")
    // A dev server is never up-to-date (the JVM exiting 0 would otherwise record a skippable success).
    outputs.upToDateWhen { false }
}

bml {
    // Dump the generated Kotlin to build/generated/bml/kotlin so it's inspectable/debuggable.
    generateKotlinSources.set(true)
}

boscaGraphql {
    endpoint.set(providers.environmentVariable("BML_GRAPHQL_ENDPOINT").orElse("http://localhost:8080/graphql"))
    packageName.set("bosca.messages.graphql")
}
