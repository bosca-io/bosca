package bosca.core.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.prefs.Preferences as JavaPreferences

class DesktopPreferences : Preferences {
    private val prefs = JavaPreferences.userNodeForPackage(DesktopPreferences::class.java)
    private val flows = mutableMapOf<String, MutableStateFlow<String?>>()

    override fun getString(key: String): Flow<String?> {
        return flows.getOrPut(key) {
            MutableStateFlow(prefs.get(key, null))
        }.asStateFlow()
    }

    override suspend fun setString(key: String, value: String?) {
        if (value == null) {
            prefs.remove(key)
        } else {
            prefs.put(key, value)
        }
        prefs.flush()
        prefs.sync()
        flows[key]?.value = value
    }
}
