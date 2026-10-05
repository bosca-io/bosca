package bosca.ecommerce.graphql

import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreType
import bosca.ecommerce.service.CatalogProductService
import bosca.ecommerce.service.CatalogService
import bosca.ecommerce.service.CompanyService
import bosca.ecommerce.service.ProviderService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID

/** Field wiring for the `Store` GraphQL type. */
@TypeController
class StoreController(
    private val companyService: CompanyService,
    private val catalogService: CatalogService,
    private val catalogProductService: CatalogProductService,
    private val providerService: ProviderService,
) : GraphQLController<Store> {

    @Field
    fun id(source: Store): UUID = source.id

    @Field
    fun identifier(source: Store): String = source.identifier

    @Field
    fun name(source: Store): String = source.name

    @Field
    suspend fun company(source: Store): Company =
        companyService.get(source.companyId) ?: error("company ${source.companyId} not found")

    @Field
    suspend fun catalog(source: Store): Catalog =
        catalogService.get(source.catalogId) ?: error("catalog ${source.catalogId} not found")

    @Field
    fun type(source: Store): StoreType = source.type

    @Field
    suspend fun paymentProvider(source: Store): PaymentProvider? =
        providerService.getPaymentProvider(source.paymentProviderId)

    @Field
    suspend fun shippingCatalogProduct(source: Store): CatalogProduct =
        catalogProductService.get(source.shippingCatalogProductId)
            ?: error("shipping catalog product ${source.shippingCatalogProductId} not found")

    @Field
    fun cartExpirationSeconds(source: Store): Int = source.cartExpirationSeconds
}
