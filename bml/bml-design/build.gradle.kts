plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.bosca.bml")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    api(project(":bml:core-bml"))
    implementation(project(":bml:bml-annotations"))
}

bml {
    packageName.set("bosca.design.generated")
    library {
        exports.set(listOf("button", "card", "field", "icon", "input", "page-shell", "spinner"))
    }
}
