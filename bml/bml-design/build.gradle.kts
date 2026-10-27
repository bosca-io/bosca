plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("io.bosca.bml")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    api(project(":bml:core-bml"))
    implementation(project(":bml:bml-annotations"))
    api(libs.kotlinx.serialization.json)
    api(project(":bosca-kmp:dom-shared"))
    api(project(":content:core-content"))
    testImplementation(kotlin("test-junit"))
}

bml {
    packageName.set("bosca.design.generated")
    library {
        exports.set(listOf(
            "alert", "app-header", "app-shell", "badge", "bosca-mark", "button", "card", "chart", "checkbox", "comment-editor", "confirm-dialog", "copy", "copy-field", "dashboard-grid", "dashboard-item", "dialog", "disclosure-heading",
            "document-editor", "empty-state", "field", "flow-editor", "fullscreen-button", "icon", "icon-button", "icon-link", "input", "json-editor",
            "loading-state", "page-heading", "page-shell", "panel", "popover", "profile", "profile-menu", "search-input", "select", "section", "section-heading", "segmented-control", "source-view", "spinner", "switch", "table-scroll", "tabs", "time", "toast", "tree", "tree-item", "tooltip", "view-tabs", "workspace",
        ))
    }
}
