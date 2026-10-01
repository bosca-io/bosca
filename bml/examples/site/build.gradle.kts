// Reference build for a BML site. Apply the BML Gradle plugin; it generates a
// Kotlin render object per `.bml` under `src/main/bml/`.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.bosca.bml")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

bml {
    sourceDir.set(layout.projectDirectory.dir("src/main/bml"))
    packageName.set("sample.site.generated")
}

dependencies {
    implementation(project(":bml:core-bml"))   // render runtime + GraphQL client
}
