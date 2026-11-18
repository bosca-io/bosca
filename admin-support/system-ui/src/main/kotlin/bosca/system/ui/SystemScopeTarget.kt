package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemScopeTarget(val namespace: String, val name: String, val type: String, val versions: List<String>) {
    val path: String get() = "$namespace/$name"
}
