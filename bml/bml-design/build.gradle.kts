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
            "alert", "app-header", "badge", "bosca-mark", "button", "card", "checkbox", "disclosure-heading",
            "empty-state", "field", "icon", "input",
            "loading-state", "page-heading", "page-shell", "panel", "section", "section-heading", "spinner", "switch", "table-scroll",
        ))
    }
}
