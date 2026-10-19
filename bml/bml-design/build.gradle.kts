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
            "alert", "app-header", "badge", "bosca-mark", "button", "card", "checkbox", "comment-editor", "confirm-dialog", "copy", "copy-field", "dialog", "disclosure-heading",
            "empty-state", "field", "icon", "icon-button", "icon-link", "input", "json-editor",
            "loading-state", "page-heading", "page-shell", "panel", "popover", "search-input", "select", "section", "section-heading", "segmented-control", "source-view", "spinner", "switch", "table-scroll", "tabs", "time", "toast", "tree", "tree-item", "tooltip", "view-tabs",
        ))
    }
}
