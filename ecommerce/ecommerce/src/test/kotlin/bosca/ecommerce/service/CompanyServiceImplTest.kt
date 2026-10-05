package bosca.ecommerce.service

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.CompanyCredit
import bosca.ecommerce.model.CompanyCreditInput
import bosca.ecommerce.model.CompanyInput
import bosca.ecommerce.model.LengthUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.WeightUnit
import bosca.ecommerce.repository.CompanyCreditRepository
import bosca.ecommerce.repository.CompanyRepository
import bosca.profile.organization.model.Organization
import bosca.profile.organization.service.OrganizationService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**company orchestration. Audit is attributed to the acting principal (no profile lookups). */
@OptIn(ExperimentalUuidApi::class)
class CompanyServiceImplTest {

    private val companyRepository = mockk<CompanyRepository>()
    private val companyCreditRepository = mockk<CompanyCreditRepository>()
    private val organizationService = mockk<OrganizationService>()
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: CompanyServiceImpl

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        service = CompanyServiceImpl(companyRepository, companyCreditRepository, organizationService, auditService)
    }

    @AfterTest
    fun teardown() = unmockkAll()

    @Test
    fun `create provisions org+profile, writes the company row, and audits`() = runTest {
        val orgId = UUID.random()
        val profileId = UUID.random()
        val companyId = UUID.random()
        val actor = UUID.random()

        val organization = mockk<Organization> {
            every { id } returns orgId
            every { this@mockk.profileId } returns profileId
        }
        coEvery { organizationService.add(any(), any(), actor) } returns organization
        val company = Company(id = companyId, organizationId = orgId, profileId = profileId)
        coEvery { companyRepository.add(any()) } returns company

        val result = service.create(CompanyInput(name = "Acme"), actor)

        assertEquals(company, result)
        coVerify(exactly = 1) { organizationService.add(any(), any(), actor) }
        coVerify(exactly = 1) {
            companyRepository.add(match { it.organizationId == orgId && it.profileId == profileId })
        }
        coVerify(exactly = 1) {
            auditService.record<Company>(eq("company"), eq(companyId), eq("created"), any(), any(), any(), eq(actor), any(), any(), any())
        }
    }

    @Test
    fun `setUnits updates the company units and audits the change`() = runTest {
        val companyId = UUID.random()
        val actor = UUID.random()
        val existing = Company(id = companyId, organizationId = UUID.random(), profileId = UUID.random())
        val updated = existing.copy(lengthUnit = LengthUnit.CENTIMETERS, weightUnit = WeightUnit.KILOGRAMS)
        coEvery { companyRepository.get(companyId) } returns existing
        coEvery { companyRepository.updateUnits(companyId, LengthUnit.CENTIMETERS, WeightUnit.KILOGRAMS) } returns updated

        val result = service.setUnits(companyId, LengthUnit.CENTIMETERS, WeightUnit.KILOGRAMS, actor)

        assertEquals(updated, result)
        coVerify(exactly = 1) {
            companyRepository.updateUnits(companyId, LengthUnit.CENTIMETERS, WeightUnit.KILOGRAMS)
        }
        coVerify(exactly = 1) {
            auditService.record<Company>(eq("company"), eq(companyId), eq("units_updated"), any(), any(), any(), eq(actor), any(), any(), any())
        }
    }

    @Test
    fun `setUnits returns null and does not audit when the company is missing`() = runTest {
        val companyId = UUID.random()
        coEvery { companyRepository.get(companyId) } returns null

        assertNull(service.setUnits(companyId, LengthUnit.INCHES, WeightUnit.POUNDS, principalId = null))

        coVerify(exactly = 0) { companyRepository.updateUnits(any(), any(), any()) }
        coVerify(exactly = 0) { auditService.record<Company>(eq("company"), any(), eq("units_updated"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addCredit issues the credit and audits it`() = runTest {
        val companyId = UUID.random()
        val creditId = UUID.random()
        val actor = UUID.random()

        val credit = CompanyCredit(id = creditId, companyId = companyId, number = "GC-1", balance = Money.of("50.00"))
        coEvery { companyCreditRepository.add(any()) } returns credit

        val result = service.addCredit(companyId, CompanyCreditInput(number = "GC-1", balance = Money.of("50.00")), actor)

        assertEquals(credit, result)
        coVerify(exactly = 1) {
            companyCreditRepository.add(match { it.companyId == companyId && it.number == "GC-1" })
        }
        coVerify(exactly = 1) {
            auditService.record<CompanyCredit>(eq("company_credit"), eq(creditId), eq("created"), any(), any(), any(), eq(actor), any(), any(), any())
        }
    }

    @Test
    fun `addCredit generates a Luhn-valid 14-digit number when none is supplied`() = runTest {
        val slot = io.mockk.slot<CompanyCredit>()
        coEvery { companyCreditRepository.getByNumber(any()) } returns null
        coEvery { companyCreditRepository.add(capture(slot)) } answers { slot.captured.copy(id = UUID.random()) }

        service.addCredit(UUID.random(), CompanyCreditInput(number = null, balance = Money.of("25.00")), principalId = null)

        val n = slot.captured.number
        assertTrue(Regex("^20\\d{12}$").matches(n), "expected a 14-digit number prefixed 20, got $n")
        assertTrue(luhn(n), "expected a Luhn-valid number, got $n")
    }

    @Test
    fun `editCredit updates the editable fields and audits the change`() = runTest {
        val id = UUID.random()
        val actor = UUID.random()
        val newAccount = UUID.random()
        val expires = OffsetDateTime.now().plusDays(30)
        val existing = CompanyCredit(id = id, companyId = UUID.random(), number = "20111", balance = Money.of("50.00"), paid = Money.of("10.00"))
        val updated = existing.copy(accountId = newAccount, description = "VIP", balance = Money.of("75.00"), expires = expires)
        coEvery { companyCreditRepository.get(id) } returns existing
        val slot = slot<CompanyCredit>()
        coEvery { companyCreditRepository.update(capture(slot)) } returns updated

        val result = service.editCredit(
            id,
            CompanyCreditInput(accountId = newAccount, number = "ignored", description = "VIP", balance = Money.of("75.00"), expires = expires),
            actor,
        )

        assertEquals(updated, result)
        // number + paid are immutable: the row passed to update keeps the existing values for both.
        assertEquals("20111", slot.captured.number)
        assertEquals(Money.of("10.00"), slot.captured.paid)
        assertEquals(newAccount, slot.captured.accountId)
        assertEquals("VIP", slot.captured.description)
        assertEquals(Money.of("75.00"), slot.captured.balance)
        assertEquals(expires, slot.captured.expires)
        coVerify(exactly = 1) {
            auditService.record<CompanyCredit>(eq("company_credit"), eq(id), eq("updated"), any(), any(), any(), eq(actor), any(), any(), any())
        }
    }

    @Test
    fun `editCredit throws when the credit is absent`() = runTest {
        val id = UUID.random()
        coEvery { companyCreditRepository.get(id) } returns null

        assertFailsWith<IllegalStateException> {
            service.editCredit(id, CompanyCreditInput(balance = Money.of("5.00")), principalId = null)
        }
        coVerify(exactly = 0) { companyCreditRepository.update(any()) }
        coVerify(exactly = 0) {
            auditService.record<CompanyCredit>(eq("company_credit"), any(), eq("updated"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `editCredit throws when the update returns null`() = runTest {
        val id = UUID.random()
        val existing = CompanyCredit(id = id, companyId = UUID.random(), number = "20112", balance = Money.of("50.00"))
        coEvery { companyCreditRepository.get(id) } returns existing
        coEvery { companyCreditRepository.update(any()) } returns null

        assertFailsWith<IllegalStateException> {
            service.editCredit(id, CompanyCreditInput(balance = Money.of("5.00")), principalId = null)
        }
        coVerify(exactly = 0) {
            auditService.record<CompanyCredit>(eq("company_credit"), any(), eq("updated"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `deleteCredit soft-deletes and audits when the credit exists`() = runTest {
        val id = UUID.random()
        val actor = UUID.random()
        val existing = CompanyCredit(id = id, companyId = UUID.random(), number = "20113", balance = Money.of("50.00"))
        coEvery { companyCreditRepository.get(id) } returns existing
        coEvery { companyCreditRepository.softDelete(id) } returns Unit

        assertTrue(service.deleteCredit(id, actor))

        coVerify(exactly = 1) { companyCreditRepository.softDelete(id) }
        coVerify(exactly = 1) {
            auditService.record<CompanyCredit>(eq("company_credit"), eq(id), eq("deleted"), any(), any(), any(), eq(actor), any(), any(), any())
        }
    }

    @Test
    fun `deleteCredit returns false and does nothing when the credit is absent`() = runTest {
        val id = UUID.random()
        coEvery { companyCreditRepository.get(id) } returns null

        assertFalse(service.deleteCredit(id, principalId = null))

        coVerify(exactly = 0) { companyCreditRepository.softDelete(any()) }
        coVerify(exactly = 0) {
            auditService.record<CompanyCredit>(eq("company_credit"), any(), eq("deleted"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `redeemCredit deducts the balance when the instrument covers it`() = runTest {
        val id = UUID.random()
        val credit = CompanyCredit(id = id, companyId = UUID.random(), number = "20123", balance = Money.of("50.00"))
        coEvery { companyCreditRepository.getByNumber("20123") } returns credit
        coEvery { companyCreditRepository.getForUpdate(id) } returns credit
        val slot = slot<CompanyCredit>()
        coEvery { companyCreditRepository.updateBalance(capture(slot)) } answers { slot.captured }

        val ok = service.redeemCredit("20123", Money.of("10.00"), accountId = null)

        assertTrue(ok)
        assertEquals(Money.of("40.00"), slot.captured.balance)
        assertEquals(Money.of("10.00"), slot.captured.paid)
    }

    @Test
    fun `redeemCredit fails (no deduction) when the balance is insufficient`() = runTest {
        val id = UUID.random()
        val credit = CompanyCredit(id = id, companyId = UUID.random(), number = "20999", balance = Money.of("5.00"))
        coEvery { companyCreditRepository.getByNumber("20999") } returns credit
        coEvery { companyCreditRepository.getForUpdate(id) } returns credit

        assertFalse(service.redeemCredit("20999", Money.of("10.00"), accountId = null))
        coVerify(exactly = 0) { companyCreditRepository.updateBalance(any()) }
    }

    @Test
    fun `redeemCredit fails when the instrument has expired`() = runTest {
        val id = UUID.random()
        val credit = CompanyCredit(id = id, companyId = UUID.random(), number = "20888", balance = Money.of("50.00"), expires = OffsetDateTime.now().minusDays(1))
        coEvery { companyCreditRepository.getByNumber("20888") } returns credit
        coEvery { companyCreditRepository.getForUpdate(id) } returns credit

        assertFalse(service.redeemCredit("20888", Money.of("10.00"), accountId = null))
    }

    @Test
    fun `redeemCredit fails when restricted to a different account`() = runTest {
        val bound = UUID.random()
        val credit = CompanyCredit(id = UUID.random(), companyId = UUID.random(), accountId = bound, number = "20777", balance = Money.of("50.00"))
        coEvery { companyCreditRepository.getByNumber("20777") } returns credit

        assertFalse(service.redeemCredit("20777", Money.of("10.00"), accountId = UUID.random()))
    }

    @Test
    fun `redeemCredit fails when the number is unknown`() = runTest {
        coEvery { companyCreditRepository.getByNumber(any()) } returns null
        assertFalse(service.redeemCredit("nope", Money.of("10.00"), accountId = null))
    }

    @Test
    fun `redeemCredit fails when an account-bound credit is redeemed with no account`() = runTest {
        // credit.accountId != null but the redeemer presents accountId = null -> the second arm of
        // `accountId != null && accountId != accountId` is true, so the redemption is refused.
        val bound = UUID.random()
        val credit = CompanyCredit(id = UUID.random(), companyId = UUID.random(), accountId = bound, number = "20733", balance = Money.of("50.00"))
        coEvery { companyCreditRepository.getByNumber("20733") } returns credit

        assertFalse(service.redeemCredit("20733", Money.of("10.00"), accountId = null))
        coVerify(exactly = 0) { companyCreditRepository.getForUpdate(any()) }
    }

    @Test
    fun `get delegates to the repository`() = runTest {
        val id = UUID.random()
        val company = Company(id = id, organizationId = UUID.random(), profileId = UUID.random())
        coEvery { companyRepository.get(id) } returns company

        assertEquals(company, service.get(id))
    }

    @Test
    fun `get returns null when the company is missing`() = runTest {
        val id = UUID.random()
        coEvery { companyRepository.get(id) } returns null

        assertNull(service.get(id))
    }

    @Test
    fun `getByIds delegates to the repository`() = runTest {
        val id = UUID.random()
        val company = Company(id = id, organizationId = UUID.random(), profileId = UUID.random())
        coEvery { companyRepository.getByIds(listOf(id)) } returns listOf(company)

        assertEquals(listOf(company), service.getByIds(listOf(id)))
    }

    @Test
    fun `getAll delegates with paging`() = runTest {
        val companies = listOf(Company(id = UUID.random(), organizationId = UUID.random(), profileId = UUID.random()))
        coEvery { companyRepository.getAll(2, 7) } returns companies

        assertEquals(companies, service.getAll(2, 7))
        coVerify(exactly = 1) { companyRepository.getAll(2, 7) }
    }

    @Test
    fun `setUnits returns null and does not audit when the unit update returns null`() = runTest {
        val companyId = UUID.random()
        val existing = Company(id = companyId, organizationId = UUID.random(), profileId = UUID.random())
        coEvery { companyRepository.get(companyId) } returns existing
        coEvery { companyRepository.updateUnits(companyId, LengthUnit.INCHES, WeightUnit.POUNDS) } returns null

        assertNull(service.setUnits(companyId, LengthUnit.INCHES, WeightUnit.POUNDS, principalId = null))

        coVerify(exactly = 0) {
            auditService.record<Company>(eq("company"), any(), eq("units_updated"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `delete soft-deletes and audits when the company exists`() = runTest {
        val companyId = UUID.random()
        val existing = Company(id = companyId, organizationId = UUID.random(), profileId = UUID.random())
        coEvery { companyRepository.get(companyId) } returns existing
        coEvery { companyRepository.softDelete(companyId) } returns Unit

        assertTrue(service.delete(companyId))

        coVerify(exactly = 1) { companyRepository.softDelete(companyId) }
        coVerify(exactly = 1) {
            auditService.record<Company>(eq("company"), eq(companyId), eq("deleted"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `delete returns false and does nothing when the company is missing`() = runTest {
        val companyId = UUID.random()
        coEvery { companyRepository.get(companyId) } returns null

        assertFalse(service.delete(companyId))

        coVerify(exactly = 0) { companyRepository.softDelete(any()) }
        coVerify(exactly = 0) {
            auditService.record<Company>(eq("company"), any(), eq("deleted"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `getCredit delegates to the repository`() = runTest {
        val id = UUID.random()
        val credit = CompanyCredit(id = id, companyId = UUID.random(), number = "20111", balance = Money.of("5.00"))
        coEvery { companyCreditRepository.get(id) } returns credit

        assertEquals(credit, service.getCredit(id))
    }

    @Test
    fun `getCreditByNumber delegates to the repository`() = runTest {
        val credit = CompanyCredit(id = UUID.random(), companyId = UUID.random(), number = "20222", balance = Money.of("5.00"))
        coEvery { companyCreditRepository.getByNumber("20222") } returns credit

        assertEquals(credit, service.getCreditByNumber("20222"))
    }

    @Test
    fun `getCredits delegates with paging`() = runTest {
        val companyId = UUID.random()
        val credits = listOf(CompanyCredit(id = UUID.random(), companyId = companyId, number = "20333", balance = Money.of("5.00")))
        coEvery { companyCreditRepository.getByCompany(companyId, 1, 3) } returns credits

        assertEquals(credits, service.getCredits(companyId, 1, 3))
        coVerify(exactly = 1) { companyCreditRepository.getByCompany(companyId, 1, 3) }
    }

    @Test
    fun `addCredit generates a number when the supplied number is blank`() = runTest {
        val slot = slot<CompanyCredit>()
        coEvery { companyCreditRepository.add(capture(slot)) } answers { slot.captured.copy(id = UUID.random()) }

        service.addCredit(UUID.random(), CompanyCreditInput(number = "   ", balance = Money.of("25.00")), principalId = null)

        val n = slot.captured.number
        assertTrue(Regex("^20\\d{12}$").matches(n), "expected a generated 14-digit number prefixed 20, got $n")
        assertTrue(luhn(n), "expected a Luhn-valid number, got $n")
    }

    @Test
    fun `addCredit honors an explicit account binding and zero paid balance`() = runTest {
        val companyId = UUID.random()
        val accountId = UUID.random()
        val slot = slot<CompanyCredit>()
        coEvery { companyCreditRepository.add(capture(slot)) } answers { slot.captured.copy(id = UUID.random()) }

        service.addCredit(
            companyId,
            CompanyCreditInput(accountId = accountId, number = "20444", balance = Money.of("30.00")),
            principalId = null,
        )

        assertEquals(accountId, slot.captured.accountId)
        assertEquals(Money.ZERO, slot.captured.paid)
        assertEquals("20444", slot.captured.number)
    }

    @Test
    fun `redeemCredit succeeds when restricted to the redeeming account`() = runTest {
        val id = UUID.random()
        val accountId = UUID.random()
        val credit = CompanyCredit(id = id, companyId = UUID.random(), accountId = accountId, number = "20555", balance = Money.of("50.00"))
        coEvery { companyCreditRepository.getByNumber("20555") } returns credit
        coEvery { companyCreditRepository.getForUpdate(id) } returns credit
        val slot = slot<CompanyCredit>()
        coEvery { companyCreditRepository.updateBalance(capture(slot)) } answers { slot.captured }

        assertTrue(service.redeemCredit("20555", Money.of("20.00"), accountId = accountId))
        assertEquals(Money.of("30.00"), slot.captured.balance)
        assertEquals(Money.of("20.00"), slot.captured.paid)
    }

    @Test
    fun `redeemCredit succeeds for an unexpired future-dated instrument`() = runTest {
        val id = UUID.random()
        val credit = CompanyCredit(
            id = id, companyId = UUID.random(), number = "20666",
            balance = Money.of("50.00"), expires = OffsetDateTime.now().plusDays(1),
        )
        coEvery { companyCreditRepository.getByNumber("20666") } returns credit
        coEvery { companyCreditRepository.getForUpdate(id) } returns credit
        val slot = slot<CompanyCredit>()
        coEvery { companyCreditRepository.updateBalance(capture(slot)) } answers { slot.captured }

        assertTrue(service.redeemCredit("20666", Money.of("10.00"), accountId = null))
        assertEquals(Money.of("40.00"), slot.captured.balance)
    }

    @Test
    fun `redeemCredit fails when the lock acquisition returns null`() = runTest {
        val id = UUID.random()
        val credit = CompanyCredit(id = id, companyId = UUID.random(), number = "20700", balance = Money.of("50.00"))
        coEvery { companyCreditRepository.getByNumber("20700") } returns credit
        coEvery { companyCreditRepository.getForUpdate(id) } returns null

        assertFalse(service.redeemCredit("20700", Money.of("10.00"), accountId = null))
        coVerify(exactly = 0) { companyCreditRepository.updateBalance(any()) }
    }

    @Test
    fun `redeemCredit fails when the balance update returns null`() = runTest {
        val id = UUID.random()
        val credit = CompanyCredit(id = id, companyId = UUID.random(), number = "20800", balance = Money.of("50.00"))
        coEvery { companyCreditRepository.getByNumber("20800") } returns credit
        coEvery { companyCreditRepository.getForUpdate(id) } returns credit
        coEvery { companyCreditRepository.updateBalance(any()) } returns null

        assertFalse(service.redeemCredit("20800", Money.of("10.00"), accountId = null))
        coVerify(exactly = 0) {
            auditService.record<CompanyCredit>(eq("company_credit"), any(), eq("redeemed"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `redeemCredit succeeds for an exact-balance redemption`() = runTest {
        val id = UUID.random()
        val credit = CompanyCredit(id = id, companyId = UUID.random(), number = "20900", balance = Money.of("10.00"))
        coEvery { companyCreditRepository.getByNumber("20900") } returns credit
        coEvery { companyCreditRepository.getForUpdate(id) } returns credit
        val slot = slot<CompanyCredit>()
        coEvery { companyCreditRepository.updateBalance(capture(slot)) } answers { slot.captured }

        assertTrue(service.redeemCredit("20900", Money.of("10.00"), accountId = null))
        assertEquals(Money.ZERO, slot.captured.balance)
        coVerify(exactly = 1) {
            auditService.record<CompanyCredit>(eq("company_credit"), eq(id), eq("redeemed"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `create passes supplied attributes through to the organization`() = runTest {
        // CompanyInput.attributes is non-null here, so the `input.attributes ?: JsonObject(emptyMap())`
        // takes its left arm and forwards the provided attributes verbatim.
        val orgId = UUID.random()
        val profileId = UUID.random()
        val attributes = kotlinx.serialization.json.JsonObject(
            mapOf("tier" to kotlinx.serialization.json.JsonPrimitive("gold")),
        )
        val organization = mockk<Organization> {
            every { id } returns orgId
            every { this@mockk.profileId } returns profileId
        }
        val orgInput = slot<bosca.profile.organization.model.OrganizationInput>()
        coEvery { organizationService.add(capture(orgInput), any(), any()) } returns organization
        coEvery { companyRepository.add(any()) } returns Company(id = UUID.random(), organizationId = orgId, profileId = profileId)

        service.create(CompanyInput(name = "Acme", attributes = attributes), principalId = null)

        assertEquals(attributes, orgInput.captured.attributes)
    }

    @Test
    fun `addCredit generates a Luhn number even when every doubled digit overflows nine`() = runTest {
        // Drive the generator's random body to all 9s so luhnCheckDigit's `if (d > 9) d -= 9` arm fires
        // on every doubled position (9*2 = 18 -> 9). The number must still be a valid 14-digit Luhn.
        io.mockk.mockkObject(kotlin.random.Random.Default)
        try {
            io.mockk.every { kotlin.random.Random.Default.nextInt(10) } returns 9
            val slot = slot<CompanyCredit>()
            coEvery { companyCreditRepository.add(capture(slot)) } answers { slot.captured.copy(id = UUID.random()) }

            service.addCredit(UUID.random(), CompanyCreditInput(number = null, balance = Money.of("25.00")), principalId = null)

            val n = slot.captured.number
            assertTrue(Regex("^20\\d{12}$").matches(n), "expected a 14-digit number prefixed 20, got $n")
            assertTrue(luhn(n), "expected a Luhn-valid number, got $n")
        } finally {
            io.mockk.unmockkObject(kotlin.random.Random.Default)
        }
    }

    /** Standard Luhn validation (doubling from the rightmost digit). */
    private fun luhn(number: String): Boolean {
        var sum = 0
        var double = false
        for (i in number.indices.reversed()) {
            var d = number[i] - '0'
            if (double) { d *= 2; if (d > 9) d -= 9 }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }
}
