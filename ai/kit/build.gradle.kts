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
    implementation(project(":content:core-content"))
    implementation(project(":social:core-profile"))
    implementation(project(":analytics:core-analytics"))
    implementation(project(":content:core-localization"))
    implementation(project(":workops:core-workops"))
    // Scripting: Kit's script sub-agent manages server-side Kotlin scripts
    // (ScriptService/ScriptExecutionService/Engine, all core contracts).
    implementation(project(":scripting:core-scripting"))
    // Pipeline authoring consumes only the engine's public contracts. Save/dry-run/run still pass
    // through GraphQL so the same authorization and boundary side effects as Studio are preserved.
    implementation(project(":pipelines:core-pipelines"))
    // Google GenAI SDK: Kit's image sub-agent calls Gemini directly to generate/edit images. This is a
    // deliberate, provider-specific exception to Kit's otherwise Koog-provider-agnostic design.
    implementation(libs.google.genai)
    // Object storage: large agent checkpoints (AgentCheckpointData) are written as blobs here,
    // not into a Postgres column — only the small pointer lives on ChatSession.latestCheckpointId.
    implementation(project(":bosca-core:core-storage"))
    // Real domain models Kit reuses (do not duplicate): tiptap document DOM and Bible.
    implementation(project(":bosca-kmp:dom-shared"))
    implementation(project(":bosca-kmp:bible-dom-shared"))

    // Koog — AI agent framework that Kit (V2) is built on. See https://docs.koog.ai.
    implementation(libs.koog.agents)
    // GOAP planner: Kit plans/sequences its actions over a typed state.
    implementation(libs.koog.agents.planner)
    // Persistence (snapshot) feature: save/restore a Kit session (checkpoints of the planner state).
    implementation(libs.koog.agents.snapshot)
    // EventHandler feature: react to agent lifecycle (write Kit's final turn to chat history for the UI).
    implementation(libs.koog.agents.event.handler)
    // ChatMemory feature: give the conversing sub-agents memory of the conversation across turns.
    implementation(libs.koog.agents.memory)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.koog.agents.test)
}

ksp {
    arg("ProviderRegistrarPrefix", "Kit")
}
