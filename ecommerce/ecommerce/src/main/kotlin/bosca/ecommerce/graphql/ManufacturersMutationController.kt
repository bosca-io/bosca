package bosca.ecommerce.graphql

import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.ManufacturerInput
import bosca.ecommerce.service.ManufacturerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Manufacturer creates under `EcomMutation.manufacturers`. Admin-gated. */
@TypeController
class ManufacturersMutationController(
    private val manufacturerService: ManufacturerService,
    private val groups: GroupEvaluator,
) : GraphQLController<ManufacturersMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, input: ManufacturerInput): Manufacturer {
        groups.verifyEcomAdmin(authentication)
        return manufacturerService.create(input, authentication.principal()?.id)
    }

    @Field
    fun manufacturer(id: UUID): ManufacturerMutation = ManufacturerMutation(id)
}
