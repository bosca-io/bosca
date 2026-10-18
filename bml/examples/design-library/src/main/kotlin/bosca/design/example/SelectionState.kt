package bosca.design.example

import kotlinx.serialization.Serializable

/** Caller-owned query, selected views and action outcomes for the compiled consumer. */
@Serializable
class SelectionState(val id: String) {
    var query: String = "Initial $id"
        private set
    var mode: String = "content"
        private set
    var selected: String = "Preview"
        private set
    var updates: Int = 0
        private set
    var submissions: Int = 0
        private set
    var result: String = ""
        private set
    var error: String = ""
        private set
    var disabled: Boolean = false
        private set
    var mounted: Boolean = true
        private set
    var changed: Boolean = false
        private set

    fun search(query: String, mode: String) {
        this.query = query
        this.mode = mode
        result = "$query / $mode"
        submissions++
        error = ""
    }

    fun fail(query: String, mode: String) {
        this.query = query
        this.mode = mode
        error = "Search rejected; your query is retained"
    }

    fun choose(mode: String) {
        this.mode = mode
    }

    fun chooseView(selected: String) {
        this.selected = selected
    }

    fun update() {
        updates++
    }

    fun change() {
        changed = !changed
        query = if (changed) "Changed $id" else "Initial $id"
        mode = if (changed) "paths" else "content"
        selected = if (changed) "Source" else "Preview"
    }

    fun toggleDisabled() {
        disabled = !disabled
    }

    fun toggle() {
        mounted = !mounted
    }
}
