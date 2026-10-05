package bosca.cli.api

import kotlinx.serialization.Serializable

@Serializable
data class KeyValue(val value: String? = null) {

    override fun toString() = value ?: "<null>"
}