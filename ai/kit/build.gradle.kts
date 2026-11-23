plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    implementation(project(":bosca-core:core"))
    implementation(project(":ai:core-ai"))
    // Platform auth + content: Kit reuses Bosca's AuthenticationContext and
    // PermissionEvaluator model rather than defining its own permissions.
    implementation(project(":bosca-core:core-security"))
    implementation(project(":bosca-core:core-configuration"))
    implementation(project(":content:core-content"))
    implementation(project(":content:core-localization"))
    implementation(project(":workops:core-workops"))
    implementation(project(":git:core-git"))
    // KSP-generated @JobDefinition executors reference core-pipelines (PipelineContext).
    implementation(project(":pipelines:core-pipelines"))
    // Google GenAI SDK: KitImageGenerationService calls Gemini directly to generate/edit images. This is a
    // deliberate, provider-specific exception to Kit's otherwise Koog-provider-agnostic design.
    implementation(libs.google.genai)
    // Object storage: runtime archives and generated images.
    implementation(project(":bosca-core:core-storage"))
    // Real domain model Kit reuses (do not duplicate): the tiptap document DOM.
    implementation(project(":bosca-kmp:dom-shared"))

    // Koog — AI agent framework that Kit (V2) is built on. See https://docs.koog.ai.
    implementation(libs.koog.agents)

    implementation(project(":kubernetes:core-kubernetes"))
    implementation(project(":sharedqueue:sharedqueue"))
    implementation(libs.ktor.client.cio)
    implementation(libs.acp)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.websockets)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(project(":bosca-core:test-support"))
    testImplementation(project(":bosca-core:storage"))
    testImplementation(project(":git:git"))
    testImplementation(project(":ai:ai"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.koog.agents.test)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
}

ksp {
    arg("ProviderRegistrarPrefix", "Kit")
}

// The lifecycle test runs the same CLI runtime used by the container.
tasks.test {
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    dependsOn(":cli:installDist")
    inputs.dir(rootProject.layout.projectDirectory.dir("cli/build/install/bosca"))
    inputs.files(providers.environmentVariable("KIT_RUNTIME_CLI")).optional()
    exclude("**/KitRuntimeImageIntegrationTest.class")
}

// This gate operates on an already built image and must observe its current contents every time.
tasks.register<Test>("runtimeImageTest") {
    group = "verification"
    description = "Checks the built Kit runtime image under its declared isolation and file lifecycle"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/KitRuntimeImageIntegrationTest.class")
    for (name in listOf("KIT_RUNTIME_IMAGE", "KIT_RUNTIME_FAMILY", "KIT_RUNTIME_VERSION", "KIT_RUNTIME_LAYOUT")) {
        environment(name, providers.environmentVariable(name).orElse("").get())
    }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("The image and Docker resources are external state") { true }
}

// Release images are supplied explicitly; ordinary coverage must not require a local image.
kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("runtimeImageTest")
        }
    }
}
