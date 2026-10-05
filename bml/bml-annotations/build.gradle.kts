plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Intentionally dependency-light: pure markers + enums the BML compiler plugin
// discovers (FIR predicates / IR collectors) and generated code references.
// No bosca-core, no KSP, no serialization.
