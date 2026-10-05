package bosca.ecommerce.graphql

import bosca.serialization.UUID

/** GraphQL namespace marker for company creates + the per-company instance accessor. */
object CompaniesMutation

/**
 * Id-scoped mutation namespace for one company. Carries the company [id] so child mutations
 * (e.g. addCredit) never repeat it; resolved from `companies { company(id) }`.
 */
data class CompanyMutation(val id: UUID)
