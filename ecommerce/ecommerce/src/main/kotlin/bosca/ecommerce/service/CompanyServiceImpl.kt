package bosca.ecommerce.service

import bosca.db.transaction
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.CompanyCredit
import bosca.ecommerce.model.CompanyCreditInput
import bosca.ecommerce.model.CompanyInput
import bosca.ecommerce.model.LengthUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.WeightUnit
import bosca.ecommerce.repository.CompanyCreditRepository
import bosca.ecommerce.repository.CompanyRepository
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.model.OrganizationInput
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.model.ProfileInput
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlin.random.Random
import kotlinx.serialization.json.JsonObject

/**
 * Companies backed by organization profiles. [create] provisions the org + profile pair through
 * `OrganizationService.add` and writes the `ecom.companies` row in one transaction. Credit issuance
 * and balance changes write audit entries; balance spends (later) use the repository's row-locking
 * path.
 */
@ServiceImplementation
class CompanyServiceImpl(
    private val companyRepository: CompanyRepository,
    private val companyCreditRepository: CompanyCreditRepository,
    private val organizationService: OrganizationService,
    private val auditService: EcomAuditService,
) : CompanyService {

    override suspend fun get(id: UUID): Company? = companyRepository.get(id)

    override suspend fun getByIds(ids: List<UUID>): List<Company> = companyRepository.getByIds(ids)

    override suspend fun getAll(offset: Int, limit: Int): List<Company> = companyRepository.getAll(offset, limit)

    override suspend fun create(input: CompanyInput, principalId: UUID?): Company = transaction {
        val organization = organizationService.add(
            OrganizationInput(
                name = input.name,
                attributes = input.attributes ?: JsonObject(emptyMap()),
                systemAttributes = JsonObject(emptyMap()),
                visibility = ProfileVisibility.PUBLIC,
            ),
            ProfileInput(name = input.name, visibility = ProfileVisibility.PUBLIC),
            principalId,
        )
        val company = companyRepository.add(
            Company(organizationId = organization.id, profileId = organization.profileId),
        )
        auditService.record(
            entityType = "company",
            entityId = company.id,
            action = "created",
            serializer = Company.serializer(),
            after = company,
            principalId = principalId,
        )
        company
    }

    override suspend fun setUnits(
        id: UUID,
        lengthUnit: LengthUnit,
        weightUnit: WeightUnit,
        principalId: UUID?,
    ): Company? = transaction {
        val existing = companyRepository.get(id) ?: return@transaction null
        val updated = companyRepository.updateUnits(id, lengthUnit, weightUnit) ?: return@transaction null
        auditService.record(
            entityType = "company",
            entityId = id,
            action = "units_updated",
            serializer = Company.serializer(),
            before = existing,
            after = updated,
            principalId = principalId,
        )
        updated
    }

    override suspend fun delete(id: UUID): Boolean = transaction {
        val existing = companyRepository.get(id) ?: return@transaction false
        companyRepository.softDelete(id)
        auditService.record(
            entityType = "company",
            entityId = id,
            action = "deleted",
            serializer = Company.serializer(),
            before = existing,
        )
        true
    }

    override suspend fun getCredit(id: UUID): CompanyCredit? = companyCreditRepository.get(id)

    override suspend fun getCreditByNumber(number: String): CompanyCredit? =
        companyCreditRepository.getByNumber(number)

    override suspend fun getCredits(companyId: UUID, offset: Int, limit: Int): List<CompanyCredit> =
        companyCreditRepository.getByCompany(companyId, offset, limit)

    override suspend fun addCredit(companyId: UUID, input: CompanyCreditInput, principalId: UUID?): CompanyCredit =
        transaction {
            val credit = companyCreditRepository.add(
                CompanyCredit(
                    companyId = companyId,
                    accountId = input.accountId,
                    // The redeemable number is generated server-side (legacy behavior); an explicit
                    // non-blank number is still honored.
                    number = input.number?.takeIf { it.isNotBlank() } ?: generateCreditNumber(),
                    description = input.description,
                    balance = input.balance,
                    paid = Money.ZERO,
                    expires = input.expires,
                ),
            )
            auditService.record(
                entityType = "company_credit",
                entityId = credit.id,
                action = "created",
                serializer = CompanyCredit.serializer(),
                after = credit,
                principalId = principalId,
            )
            credit
        }

    override suspend fun editCredit(id: UUID, input: CompanyCreditInput, principalId: UUID?): CompanyCredit =
        transaction {
            val existing = companyCreditRepository.get(id) ?: error("company credit $id not found")
            // The redeemable number and the spent total (paid) are immutable; an edit only touches the
            // account binding, description, balance, and expiry.
            val updated = companyCreditRepository.update(
                existing.copy(
                    accountId = input.accountId,
                    description = input.description,
                    balance = input.balance,
                    expires = input.expires,
                ),
            ) ?: error("company credit $id not found")
            auditService.record(
                entityType = "company_credit",
                entityId = id,
                action = "updated",
                serializer = CompanyCredit.serializer(),
                before = existing,
                after = updated,
                principalId = principalId,
            )
            updated
        }

    override suspend fun deleteCredit(id: UUID, principalId: UUID?): Boolean = transaction {
        val existing = companyCreditRepository.get(id) ?: return@transaction false
        companyCreditRepository.softDelete(id)
        auditService.record(
            entityType = "company_credit",
            entityId = id,
            action = "deleted",
            serializer = CompanyCredit.serializer(),
            before = existing,
            principalId = principalId,
        )
        true
    }

    /**
     * Generates the redeemable number the legacy way: a 14-digit numeric, card-style number prefixed
     * "20" with a trailing Luhn check digit (legacy `CreditCardNumber.generateCreditCardWithPrefix(14, "20")`).
     * Uniqueness is backstopped by the `number` unique constraint; the 11-random-digit space makes a
     * collision negligible (the legacy didn't retry either).
     */
    override suspend fun redeemCredit(number: String, amount: Money, accountId: UUID?): Boolean = transaction {
        val credit = companyCreditRepository.getByNumber(number) ?: return@transaction false
        // A credit restricted to one account can only be redeemed by that account (legacy semantics).
        if (credit.accountId != null && credit.accountId != accountId) return@transaction false
        val locked = companyCreditRepository.getForUpdate(credit.id) ?: return@transaction false
        if (locked.expires != null && !OffsetDateTime.now().isBefore(locked.expires)) return@transaction false
        if (locked.balance < amount) return@transaction false
        val updated = companyCreditRepository.updateBalance(
            locked.copy(balance = locked.balance - amount, paid = locked.paid + amount),
        ) ?: return@transaction false
        auditService.record(
            entityType = "company_credit",
            entityId = locked.id,
            action = "redeemed",
            serializer = CompanyCredit.serializer(),
            before = locked,
            after = updated,
        )
        true
    }

    private fun generateCreditNumber(): String {
        val body = buildString {
            append("20")
            repeat(CREDIT_NUMBER_LENGTH - 1 - 2) { append(Random.nextInt(10)) }
        }
        return body + luhnCheckDigit(body)
    }

    /** The Luhn check digit for [body]: doubling starts at the rightmost body digit (units = the check digit). */
    private fun luhnCheckDigit(body: String): Int {
        var sum = 0
        var double = true
        for (i in body.indices.reversed()) {
            var d = body[i] - '0'
            if (double) { d *= 2; if (d > 9) d -= 9 }
            sum += d
            double = !double
        }
        return (10 - (sum % 10)) % 10
    }

    private companion object {
        const val CREDIT_NUMBER_LENGTH = 14
    }
}
