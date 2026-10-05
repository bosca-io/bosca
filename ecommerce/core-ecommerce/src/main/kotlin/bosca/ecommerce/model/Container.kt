package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A box type a company packs shipments into (`ecom.shipping_containers`). [width]/
 * [height]/[length]/[weight] are the container's own outer dimensions + tare weight; the `supported*`
 * fields are its inner capacity — the maximum packable dimensions and payload weight the packer
 * honors. Dimensions are unitless floats; a company picks one consistent unit.
 */
@BatchKey("id")
@Serializable
data class Container(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("company_id")
    val companyId: UUID,
    val name: String,
    val width: Double,
    val height: Double,
    val length: Double,
    val weight: Double,
    @ColumnName("supported_width")
    val supportedWidth: Double,
    @ColumnName("supported_height")
    val supportedHeight: Double,
    @ColumnName("supported_length")
    val supportedLength: Double,
    @ColumnName("supported_weight")
    val supportedWeight: Double,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
