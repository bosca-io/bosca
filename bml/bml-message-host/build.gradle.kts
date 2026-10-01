plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Hosts compiled message-project jars in child classloaders —
// load, discover templates via the well-known module entry point, render, and hot-swap versions
// atomically (drain in-flight renders, close the retired loader). Pure JVM + core-bml; the
// runner-facing service layer (artifact fetch, event subscription, registry) builds on top.
dependencies {
    api(project(":bml:core-bml")) // BmlMessageTemplate/Context/Module — the shared parent-classloader types
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json) // the jar's embedded email manifest

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    // Hot-swap tests compile REAL message jars: .bml -> bml-compiler codegen -> embedded kotlinc -> jar.
    testImplementation(project(":bml:bml-compiler"))
    testImplementation(project(":bml:bml-annotations")) // generated code references @BmlMessage/@BmlGenerated
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
}

kover {
    currentProject {
        instrumentation {
            // The unloading tests prove retired classloader generations are garbage-collected.
            // Kover's agent pins every class it instruments, so the runtime-loaded `bml.generated.*`
            // classes (from test-BUILT jars — no coverage value) must stay uninstrumented or the
            // leak tests fail under coverage while production unloads fine.
            excludedClasses.addAll("bml.generated.*")
        }
    }
}
