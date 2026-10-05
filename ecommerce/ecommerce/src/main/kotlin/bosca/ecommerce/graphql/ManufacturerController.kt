package bosca.ecommerce.graphql

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.ManufacturerExtras
import bosca.ecommerce.service.CompanyService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Field wiring for the `Manufacturer` GraphQL type. */
@TypeController
class ManufacturerController(
    private val companyService: CompanyService,
) : GraphQLController<Manufacturer> {

    @Field
    fun id(source: Manufacturer): UUID = source.id

    @Field
    suspend fun company(source: Manufacturer): Company =
        companyService.get(source.companyId) ?: error("company ${source.companyId} not found")

    @Field
    fun name(source: Manufacturer): String = source.name

    @Field
    fun extras(source: Manufacturer): ManufacturerExtras = source.extras

    @Field
    fun created(source: Manufacturer): OffsetDateTime = source.created

    @Field
    fun modified(source: Manufacturer): OffsetDateTime = source.modified
}
