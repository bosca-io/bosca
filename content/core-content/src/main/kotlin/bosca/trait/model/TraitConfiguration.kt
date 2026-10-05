package bosca.trait.model

import kotlinx.serialization.Serializable

@Serializable
data class TraitConfiguration(val traitId: String) {

    companion object Companion {
        const val ACTIVITY_ID = "metadata.trait.process"
    }
}