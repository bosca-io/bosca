package bosca.ecommerce.graphql

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.CompanyCredit
import bosca.ecommerce.model.LengthUnit
import bosca.ecommerce.model.WeightUnit
import bosca.ecommerce.service.CompanyService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.organization.model.Organization
import bosca.profile.organization.service.OrganizationService
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Field wiring for the `Company` GraphQL type. Identity resolves through the profiles domain. */
@TypeController
class CompanyController(
    private val companyService: CompanyService,
    private val organizationService: OrganizationService,
    private val profileService: ProfileService,
) : GraphQLController<Company> {

    @Field
    fun id(source: Company): UUID = source.id

    @Field
    suspend fun organization(source: Company): Organization =
        organizationService.getOrganization(source.organizationId)

    @Field
    suspend fun profile(source: Company): Profile = profileService.getById(source.profileId)

    @Field
    suspend fun credits(source: Company, offset: Int, limit: Int): List<CompanyCredit> =
        companyService.getCredits(source.id, offset, limit)

    @Field
    fun lengthUnit(source: Company): LengthUnit = source.lengthUnit

    @Field
    fun weightUnit(source: Company): WeightUnit = source.weightUnit

    @Field
    fun created(source: Company): OffsetDateTime = source.created

    @Field
    fun modified(source: Company): OffsetDateTime = source.modified
}
