package bosca.core.preferences

import kotlinx.browser.window
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class WebPreferences : Preferences {
    private val flows = mutableMapOf<String, MutableStateFlow<String?>>()

    override fun getString(key: String): Flow<String?> {
        return flows.getOrPut(key) {
            MutableStateFlow(window.localStorage.getItem(key))
        }.asStateFlow()
    }

    override suspend fun setString(key: String, value: String?) {
        if (value == null) {
            window.localStorage.removeItem(key)
        } else {
            window.localStorage.setItem(key, value)
        }
        flows[key]?.value = value
    }
}
