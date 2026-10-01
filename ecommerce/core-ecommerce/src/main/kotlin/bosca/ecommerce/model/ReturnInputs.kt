package bosca.ecommerce.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Requests a return/RMA against a paid order ([cartId]) for the given [lines]. */
@Serializable
data class ReturnInput(
    @Contextual
    val cartId: UUID,
    val reason: String? = null,
    /** How the eventual refund is issued. */
    val tender: RefundTender = RefundTender.ORIGINAL,
    /** A check number when [tender] is CHECK. */
    val checkNumber: String? = null,
    val lines: List<ReturnLineInput> = emptyList(),
)

/** One requested return line: the order's cart [itemId] and the [quantity] coming back. */
@Serializable
data class ReturnLineInput(
    @Contextual
    val itemId: UUID,
    val quantity: Int,
)
