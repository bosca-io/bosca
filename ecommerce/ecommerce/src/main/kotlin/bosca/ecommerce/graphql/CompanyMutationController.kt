package bosca.ecommerce.graphql

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.CompanyCredit
import bosca.ecommerce.model.CompanyCreditInput
import bosca.ecommerce.model.LengthUnit
import bosca.ecommerce.model.WeightUnit
import bosca.ecommerce.service.CompanyService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Mutations scoped to one company (the id carried by [CompanyMutation]). Admin-gated. */
@TypeController
class CompanyMutationController(
    private val companyService: CompanyService,
    private val groups: GroupEvaluator,
) : GraphQLController<CompanyMutation> {

    @Field
    suspend fun addCredit(
        authentication: AuthenticationContext,
        source: CompanyMutation,
        input: CompanyCreditInput,
    ): CompanyCredit {
        groups.verifyEcomAdmin(authentication)
        return companyService.addCredit(source.id, input, authentication.principal()?.id)
    }

    @Field
    suspend fun editCredit(
        authentication: AuthenticationContext,
        source: CompanyMutation,
        id: UUID,
        input: CompanyCreditInput,
    ): CompanyCredit {
        groups.verifyEcomAdmin(authentication)
        return companyService.editCredit(id, input, authentication.principal()?.id)
    }

    @Field
    suspend fun deleteCredit(
        authentication: AuthenticationContext,
        source: CompanyMutation,
        id: UUID,
    ): Boolean {
        groups.verifyEcomAdmin(authentication)
        return companyService.deleteCredit(id, authentication.principal()?.id)
    }

    @Field
    suspend fun setUnits(
        authentication: AuthenticationContext,
        source: CompanyMutation,
        lengthUnit: LengthUnit,
        weightUnit: WeightUnit,
    ): Company {
        groups.verifyEcomAdmin(authentication)
        return companyService.setUnits(source.id, lengthUnit, weightUnit, authentication.principal()?.id)
            ?: error("company not found: ${source.id}")
    }
}
