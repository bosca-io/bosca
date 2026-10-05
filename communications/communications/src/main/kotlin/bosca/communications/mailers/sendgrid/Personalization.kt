@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package bosca.communications.mailers.sendgrid

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Personalization(
    val subject: String,
    val to: List<SendGridEmail>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("custom_args")
    val customArguments: Map<String, String>? = null,
)
