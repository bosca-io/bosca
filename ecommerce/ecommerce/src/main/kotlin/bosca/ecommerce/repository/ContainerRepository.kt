package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Container
import bosca.serialization.UUID

/** Persistence for `ecom.shipping_containers` — a company's box catalog (soft-deleted). */
@Repository
interface ContainerRepository {

    @Query("select * from ecom.shipping_containers where id = :id and deleted is null")
    suspend fun get(id: UUID): Container?

    @Query("select * from ecom.shipping_containers where company_id = :companyId and deleted is null order by name")
    suspend fun getByCompany(companyId: UUID): List<Container>

    @Query(
        """
        insert into ecom.shipping_containers
            (company_id, name, width, height, length, weight, supported_width, supported_height, supported_length, supported_weight)
        values
            (:companyId, :name, :width, :height, :length, :weight, :supportedWidth, :supportedHeight, :supportedLength, :supportedWeight)
        returning *
        """,
    )
    suspend fun add(container: Container): Container

    @Query(
        """
        update ecom.shipping_containers
           set name = :name, width = :width, height = :height, length = :length, weight = :weight,
               supported_width = :supportedWidth, supported_height = :supportedHeight,
               supported_length = :supportedLength, supported_weight = :supportedWeight, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun update(container: Container): Container?

    @Query("update ecom.shipping_containers set deleted = now(), modified = now() where id = :id and deleted is null")
    suspend fun softDelete(id: UUID)
}
