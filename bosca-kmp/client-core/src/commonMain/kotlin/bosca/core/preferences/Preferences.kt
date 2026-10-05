package bosca.core.preferences

import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic key-value preference store with reactive observation.
 *
 * Provides a simple API for persisting and observing string preferences.
 * Implementations handle the underlying platform storage mechanism
 * (e.g., SharedPreferences on Android, NSUserDefaults on iOS).
 */
interface Preferences {

    /**
     * Returns a [Flow] that emits the current value associated with [key] and
     * re-emits whenever the value changes.
     *
     * @param key the preference key to observe
     * @return a flow emitting the stored value, or `null` if the key is absent
     */
    fun getString(key: String): Flow<String?>

    /**
     * Stores or removes a string value under the given [key].
     *
     * @param key the preference key
     * @param value the value to persist, or `null` to remove the entry
     */
    suspend fun setString(key: String, value: String?)
}
