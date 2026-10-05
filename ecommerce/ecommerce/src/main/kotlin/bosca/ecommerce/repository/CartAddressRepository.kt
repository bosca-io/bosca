package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.CartAddress
import bosca.serialization.UUID

/** Persistence for `ecom.cart_addresses` (one row per cart+type; [upsert] respects that uniqueness). */
@Repository
interface CartAddressRepository {

    @Query("select * from ecom.cart_addresses where cart_id = :cartId order by type")
    suspend fun getByCart(cartId: UUID): List<CartAddress>

    @Query("delete from ecom.cart_addresses where cart_id = :cartId and type = (:type)::ecom.address_type")
    suspend fun deleteByCartAndType(cartId: UUID, type: AddressType)

    @Query(
        """
        insert into ecom.cart_addresses
            (cart_id, type, first_name, last_name, address1, address2, city, state, country, zip, phone, email, note, validated)
        values
            (:cartId, (:type)::ecom.address_type, :firstName, :lastName, :address1, :address2, :city, :state, :country, :zip, :phone, :email, :note, :validated)
        on conflict (cart_id, type) do update set
            first_name = excluded.first_name, last_name = excluded.last_name, address1 = excluded.address1,
            address2 = excluded.address2, city = excluded.city, state = excluded.state, country = excluded.country,
            zip = excluded.zip, phone = excluded.phone, email = excluded.email, note = excluded.note,
            validated = excluded.validated, modified = now()
        returning *
        """,
    )
    suspend fun upsert(address: CartAddress): CartAddress
}
