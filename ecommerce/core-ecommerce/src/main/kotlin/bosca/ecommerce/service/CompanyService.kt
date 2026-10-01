package bosca.ecommerce.service

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.CompanyCredit
import bosca.ecommerce.model.CompanyCreditInput
import bosca.ecommerce.model.CompanyInput
import bosca.ecommerce.model.LengthUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.WeightUnit
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Companies and their credit instruments. A company's identity is a profiles-domain organization +
 * profile (created together with the commerce row), so [create] provisions both atomically.
 */
interface CompanyService : Service {

    /** A company by id, or null if missing/soft-deleted. */
    suspend fun get(id: UUID): Company?

    /** Companies by id (batch — backs the nested-resolver DataLoader path). */
    suspend fun getByIds(ids: List<UUID>): List<Company>

    /** All companies, paged (admin surface). */
    suspend fun getAll(offset: Int, limit: Int): List<Company>

    /**
     * Create a company: provisions the organization + profile pair via `OrganizationService.add`
     * and writes the `ecom.companies` row in one transaction. [principalId] is the acting principal
     * (owner of the new org + audit attribution), null for system creation.
     */
    suspend fun create(input: CompanyInput, principalId: UUID?): Company

    /**
     * Set the units a company's product/container dimensions and weights are stored in. These are the
     * authoritative storage units for the whole company (products span fulfillment centers, so the unit
     * can't be per-center); carrier providers convert from them at label time. Returns the updated
     * company, or null if missing/soft-deleted. Audited.
     */
    suspend fun setUnits(id: UUID, lengthUnit: LengthUnit, weightUnit: WeightUnit, principalId: UUID?): Company?

    /** Soft-delete a company. */
    suspend fun delete(id: UUID): Boolean

    /** A company credit by id. */
    suspend fun getCredit(id: UUID): CompanyCredit?

    /** A company credit by its redeemable number (natural key). */
    suspend fun getCreditByNumber(number: String): CompanyCredit?

    /** A company's credit instruments, paged. */
    suspend fun getCredits(companyId: UUID, offset: Int, limit: Int): List<CompanyCredit>

    /** Issue a numbered company credit instrument. Writes an audit entry attributed to [principalId]. */
    suspend fun addCredit(companyId: UUID, input: CompanyCreditInput, principalId: UUID?): CompanyCredit

    /** Edit an issued credit's description/balance/account/expiry (number + spent total are immutable). */
    suspend fun editCredit(id: UUID, input: CompanyCreditInput, principalId: UUID?): CompanyCredit

    /** Soft-delete an issued credit instrument. */
    suspend fun deleteCredit(id: UUID, principalId: UUID?): Boolean

    /**
     * Redeem [amount] from the credit instrument identified by [number] (row-locked). Returns false —
     * never throws — when the credit is missing, expired, restricted to a different account, or has
     * insufficient balance, so a COMPANY_CREDIT payment can record a clean FAILURE.
     */
    suspend fun redeemCredit(number: String, amount: Money, accountId: UUID?): Boolean
}
