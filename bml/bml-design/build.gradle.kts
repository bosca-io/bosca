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
        exports.set(listOf(
            "alert", "badge", "button", "card", "checkbox", "empty-state", "field", "icon", "input",
            "loading-state", "page-shell", "spinner", "switch", "table-scroll",
        ))
    }
}
