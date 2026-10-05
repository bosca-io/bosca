@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.graphql

import bosca.recommendations.model.PersonalizationSignalDefinition
import bosca.recommendations.model.PersonalizationSignalDefinitionInput
import bosca.recommendations.model.PersonalizationSignalSourceType
import bosca.recommendations.model.PersonalizationSignalValueType
import bosca.recommendations.service.PersonalizationSignalService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Field-resolver + admin-gating tests for the Personalization Signal GraphQL controllers (the
 * type controller pass-throughs and the admin-gated query/mutation surfaces).
 */
class PersonalizationSignalControllersTest {

    private val service = mockk<PersonalizationSignalService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()

    private fun signal() = PersonalizationSignalDefinition(
        id = UUID.random(),
        key = "age_band",
        sourceType = PersonalizationSignalSourceType.ATTRIBUTE,
        sourceId = "bosca.profiles.age",
        expression = "value",
        valueType = PersonalizationSignalValueType.CATEGORICAL,
        priority = 3,
        useAsFeature = true,
        useAsCohort = true,
        enabled = true,
    )

    private fun input() = PersonalizationSignalDefinitionInput(
        key = "age_band",
        sourceType = PersonalizationSignalSourceType.ATTRIBUTE,
        sourceId = "bosca.profiles.age",
        expression = "value",
        valueType = PersonalizationSignalValueType.CATEGORICAL,
    )

    // ── type controller (field pass-throughs) ──
    private val typeController = PersonalizationSignalController()

    @Test
    fun `type controller passes every field through from the model`() {
        val s = signal()
        assertEquals(s.id, typeController.id(s))
        assertEquals("age_band", typeController.key(s))
        assertEquals(PersonalizationSignalSourceType.ATTRIBUTE, typeController.sourceType(s))
        assertEquals("bosca.profiles.age", typeController.sourceId(s))
        assertEquals("value", typeController.expression(s))
        assertEquals(PersonalizationSignalValueType.CATEGORICAL, typeController.valueType(s))
        assertEquals(3, typeController.priority(s))
        assertTrue(typeController.useAsFeature(s))
        assertTrue(typeController.useAsCohort(s))
        assertTrue(typeController.enabled(s))
        assertEquals(s.created, typeController.created(s))
        assertEquals(s.modified, typeController.modified(s))
    }

    // ── query controller ──
    private val queryController = PersonalizationSignalsController(service, groupEvaluator)

    @Test
    fun `all is admin-gated and delegates`() = runTest {
        coEvery { service.getAll(0, 10) } returns listOf(signal())
        assertEquals(1, queryController.all(auth, 0, 10).size)
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
    }

    @Test
    fun `signal is admin-gated and delegates`() = runTest {
        val s = signal()
        coEvery { service.getById(s.id) } returns s
        assertSame(s, queryController.signal(auth, s.id))
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
    }

    // ── mutation controller ──
    private val mutationController = PersonalizationSignalsMutationController(service, groupEvaluator)

    @Test
    fun `add is admin-gated and delegates`() = runTest {
        val s = signal()
        coEvery { service.add(any()) } returns s
        assertSame(s, mutationController.add(auth, input()))
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
    }

    @Test
    fun `edit is admin-gated and delegates`() = runTest {
        val s = signal()
        coEvery { service.edit(s.id, any()) } returns s
        assertSame(s, mutationController.edit(auth, s.id, input()))
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
    }

    @Test
    fun `delete is admin-gated and delegates`() = runTest {
        val id = UUID.random()
        coEvery { service.delete(id) } just Runs
        assertTrue(mutationController.delete(auth, id))
        coVerify { service.delete(id) }
    }
}
