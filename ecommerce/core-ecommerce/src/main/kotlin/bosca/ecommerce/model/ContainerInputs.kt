package bosca.ecommerce.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Creates or edits a shipping container (box type). Outer dimensions + tare weight, then inner capacity. */
@Serializable
data class ContainerInput(
    @Contextual
    val companyId: UUID,
    val name: String,
    val width: Double,
    val height: Double,
    val length: Double,
    val weight: Double,
    val supportedWidth: Double,
    val supportedHeight: Double,
    val supportedLength: Double,
    val supportedWeight: Double,
)
