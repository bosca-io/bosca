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
            "schema-form", "file-field", "board", "board-column", "board-card",
            "calendar-sidebar", "calendar-event-fields", "calendar-view", "calendar-month-view", "calendar-week-view", "calendar-day-view", "form-body", "form-actions", "button-link", "pagination", "file-drop", "progress-bar", "alert", "app-header", "app-shell", "badge", "bosca-mark", "button", "card", "chart", "checkbox", "comment-editor", "confirm-dialog", "copy", "copy-field", "dashboard-grid", "dashboard-item", "dialog", "disclosure-heading",
            "document-editor", "html-content", "empty-state", "field", "help-text", "flow-editor", "fullscreen-button", "icon", "icon-button", "icon-link", "input", "color-input", "slug-input", "json-editor", "code-editor",
            "subsystem-links", "subsystem-switcher", "loading-state", "page-heading", "page-shell", "panel", "popover", "profile", "profile-menu", "search-input", "select", "section", "section-heading", "segmented-control", "source-view", "spinner", "switch", "table-scroll", "tabs", "time", "toast", "tree", "tree-item", "tooltip", "view-tabs", "workspace",
        ))
    }
}

val testClient by tasks.registering(Exec::class) {
    dependsOn("bmlInstallClientDependencies", "bmlPrepareClient")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), "--test", "src/test/color-input.test.mjs", "src/test/slug-input.test.mjs", "src/test/board.test.mjs", "src/test/form-adapters.test.mjs", "src/test/profile-menu.test.mjs", "src/test/chart-maps.test.mjs", "src/test/calendar.test.mjs", "src/test/calendar-fields.test.mjs", "src/test/schema-form.test.mjs", "src/test/dialog.test.mjs")
}

val typecheckClient = tasks.register<Exec>("typecheckClient") {
    group = "verification"
    description = "Type-check shared controls and generated BML clients."
    dependsOn("compileKotlin", "bmlPrepareClient", "bmlInstallClientDependencies")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), rootProject.file("bml/bml-runtime/node_modules/typescript/bin/tsc").absolutePath, "--noEmit")
}

tasks.named("check") { dependsOn(testClient, typecheckClient) }
