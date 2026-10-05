package bosca.ecommerce.graphql

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.CompanyInput
import bosca.ecommerce.service.CompanyService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Company creates and the per-company instance accessor under `EcomMutation.companies`. Admin-gated
 * (global administrators group) as the baseline; adds per-company permission delegation.
 */
@TypeController
class CompaniesMutationController(
    private val companyService: CompanyService,
    private val groups: GroupEvaluator,
) : GraphQLController<CompaniesMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, input: CompanyInput): Company {
        groups.verifyEcomAdmin(authentication)
        return companyService.create(input, authentication.principal()?.id)
    }

    @Field
    fun company(id: UUID): CompanyMutation = CompanyMutation(id)
}
