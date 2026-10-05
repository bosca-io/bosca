package bosca.core.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

class IOSPreferences : Preferences {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val flows = mutableMapOf<String, MutableStateFlow<String?>>()

    override fun getString(key: String): Flow<String?> {
        return flows.getOrPut(key) {
            MutableStateFlow(defaults.stringForKey(key))
        }.asStateFlow()
    }

    override suspend fun setString(key: String, value: String?) {
        if (value == null) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(value, key)
        }
        flows[key]?.value = value
    }
}
