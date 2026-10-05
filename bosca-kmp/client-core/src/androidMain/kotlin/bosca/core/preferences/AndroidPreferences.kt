package bosca.core.preferences

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android preference store backed by application-scoped SharedPreferences. */
class AndroidPreferences(context: Context) : Preferences {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val flows = mutableMapOf<String, MutableStateFlow<String?>>()

    override fun getString(key: String): Flow<String?> = flows.getOrPut(key) {
        MutableStateFlow(preferences.getString(key, null))
    }.asStateFlow()

    override suspend fun setString(key: String, value: String?) {
        preferences.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
        flows[key]?.value = value
    }

    private companion object {
        const val PREFERENCES_NAME = "bosca_preferences"
    }
}
