package bosca.design.example

import kotlinx.serialization.Serializable

/** Application-owned form values, controlled revisions and action outcomes for select examples. */
@Serializable
class SelectControlsState(val id: String) {
    var flavor: String = "b"
        private set
    var submissions: Int = 0
        private set
    var updates: Int = 0
        private set
    var revision: Int = 0
        private set
    var disabled: Boolean = false
        private set
    var loading: Boolean = false
        private set
    var mounted: Boolean = true
        private set
    var error: String = ""
        private set

    fun save(flavor: String) {
        this.flavor = flavor
        submissions++
        error = ""
    }

    fun reject(flavor: String) {
        this.flavor = flavor
        error = "Choice rejected; your selection is retained"
    }

    fun update() {
        updates++
    }

    fun change() {
        flavor = if (flavor == "a") "b" else "a"
        revision++
    }

    fun toggleDisabled() {
        disabled = !disabled
    }

    fun toggleLoading() {
        loading = !loading
    }

    fun toggle() {
        mounted = !mounted
    }
}
