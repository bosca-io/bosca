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
            "calendar-sidebar", "calendar-event-fields", "calendar-view", "calendar-month-view", "calendar-week-view", "calendar-day-view", "form-body", "form-actions", "button-link", "pagination", "file-drop", "progress-bar", "alert", "app-header", "app-shell", "badge", "bosca-mark", "button", "card", "chart", "checkbox", "comment-editor", "confirm-dialog", "copy", "copy-field", "dashboard-grid", "dashboard-item", "dialog", "disclosure-heading",
            "document-editor", "empty-state", "field", "flow-editor", "fullscreen-button", "icon", "icon-button", "icon-link", "input", "json-editor", "code-editor",
            "subsystem-links", "subsystem-switcher", "loading-state", "page-heading", "page-shell", "panel", "popover", "profile", "profile-menu", "search-input", "select", "section", "section-heading", "segmented-control", "source-view", "spinner", "switch", "table-scroll", "tabs", "time", "toast", "tree", "tree-item", "tooltip", "view-tabs", "workspace",
        ))
    }
}

val testClient by tasks.registering(Exec::class) {
    dependsOn("bmlInstallClientDependencies", "bmlPrepareClient")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), "--test", "src/test/profile-menu.test.mjs", "src/test/chart-maps.test.mjs", "src/test/calendar.test.mjs", "src/test/calendar-fields.test.mjs")
}

tasks.named("check") { dependsOn(testClient) }
