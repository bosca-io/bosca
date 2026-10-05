// Notifications — the public email-preference site built with BML. The io.bosca.bml plugin generates
// a Kotlin render object per `.bml` under src/main/bml/ AND (via the io.bosca.graphql plugin it
// applies) the typed GraphQL data layer from src/main/graphql/. Runs on the dedicated SSR server.
//
//     ./gradlew :notifications-web:run                  # -> http://localhost:9094/  (from the workspace root)
//     ./gradlew :notifications-web:downloadBoscaGraphqlSchema   # refresh src/main/graphql/schema.graphqls
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("io.bosca.bml")     // .bml render codegen; also contributes BML-sane defaults to io.bosca.graphql
    id("io.bosca.graphql") // owns the GraphQL data-layer codegen (download-schema + generate)
    application
    id("org.graalvm.buildtools.native") // deployed as a GraalVM native image (nativeCompile → Dockerfile)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    "boscaGraphqlGenerator"(project(":bosca-graphql:bosca-graphql-client"))
    implementation(project(":bml:core-bml"))          // RenderContext / HtmlWriter (generated code calls these)
    implementation(project(":bml:bml-annotations"))   // @BmlPage / @BmlGenerated (generated code references these)
    implementation(project(":bml:bml-server"))        // BmlServer — run the site as an actual SSR server
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)  // @Serializable island state models
    implementation(project(":bosca-graphql:bosca-graphql-client"))        // the generated operations reference BoscaOperation / GraphQLJson
    implementation(project(":bosca-core:core-graalvm"))          // BoscaFeature + shared native-image metadata for the bosca-core Netty stack
    runtimeOnly(libs.logback.classic)                // logging backend for the deployed server (tests inherit it)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test) // kotlinx-coroutines-test — suspend page loaders
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

application {
    mainClass.set("bosca.notifications.web.MainKt")
}
tasks.named<JavaExec>("run") {
    dependsOn("bmlBundleClient")
    // `run` IS the dev loop — live reload + no-store CSS/JS. Deployed servers (jar/dist) leave it off.
    systemProperty("bml.dev", System.getProperty("bml.dev") ?: "true")
    // The GraalVM buildtools plugin declares an agent-output dir on every JavaExec, which makes
    // `run` up-to-date-checkable — after any run whose JVM exited 0, Gradle records a success and
    // silently SKIPS starting the server next time. A dev server is never up-to-date.
    outputs.upToDateWhen { false }
}

bml {
    // Workspace dev loop — when this repo is checked out inside bosca-workspace, use the live
    // sibling bml-runtime (bundler + runtime, source-to-source). A standalone clone falls back
    // to the published @bosca/bml package, which the BML plugin resolves from node_modules
    // (declared in package.json).
    val runtimeCheckout = layout.projectDirectory.dir("../../bml/bml-runtime")
    if (runtimeCheckout.asFile.isDirectory) {
        clientBundler.set(runtimeCheckout.file("tools/bundle.mjs"))
        clientRuntime.set(runtimeCheckout.file("src/index.ts"))
    }
    generateKotlinSources.set(true)
}

// GraphQL data layer — owned by io.bosca.graphql (applied by the BML plugin with BML scalar defaults).
// The site just declares where to fetch from and the generated package; operations live in src/main/graphql.
boscaGraphql {
    endpoint.set(providers.environmentVariable("BML_GRAPHQL_ENDPOINT").orElse("http://localhost:8080/graphql"))
    packageName.set("bosca.notifications.web.graphql")
}

// The deployed artifact is a GraalVM native image (mirrors bosca-server/cli). Build-time flags
// live in src/main/resources/META-INF/native-image/bosca.notifications/notifications-web/
// native-image.properties; classpath resources the binary keeps (notifications.css, logback.xml)
// are in resource-config.json next to it.
graalvmNative {
    testSupport.set(false)
    metadataRepository {
        enabled = true
    }
    binaries {
        named("main") {
            imageName.set("notifications-web")
            mainClass.set("bosca.notifications.web.MainKt")
            buildArgs.add("-J-Xmx16g")
            buildArgs.add("-J--sun-misc-unsafe-memory-access=allow")
            buildArgs.add("-J--add-opens=java.base/java.nio=ALL-UNNAMED")
            buildArgs.add("-J--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED")
            // kotlin-compiler-embeddable (pulled in via bosca-core's jte-kotlin) embeds
            // org.jline native-image metadata that references a reflection-config.json the
            // jar doesn't contain, aborting the build. Same exclusion bosca-server uses.
            buildArgs.add("--exclude-config")
            buildArgs.add(".*/.*.jar")
            buildArgs.add("^/META-INF/native-image/org\\.jline/.*")
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
                buildArgs.add("--gc=G1")
            }
        }
    }
}

// Component-level aggregate tasks remain available under this project path.
tasks.register("cleanProjects") {
    description = "Run clean (single-project build; matches the workspace aggregator contract)"
    dependsOn(tasks.named("clean"))
}
