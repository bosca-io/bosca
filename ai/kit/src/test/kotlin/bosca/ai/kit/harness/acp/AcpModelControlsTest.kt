@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package bosca.ai.kit.harness.acp

import bosca.ai.kit.harness.KitModelSelection
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class AcpModelControlsTest {
    private val controls = AcpModelControls(Json)
    private fun option(id: String, current: String, vararg values: String) = SessionConfigOption.select(id, id, current,
        SessionConfigSelectOptions.Grouped(listOf(SessionConfigSelectGroup(SessionConfigGroupId("group"), options = values.map {
            SessionConfigSelectOption(SessionConfigValueId(it), it.uppercase())
        }))), category = SessionConfigOptionCategory(when (id) { "model" -> "model"; "reasoning_effort" -> "thought_level"; else -> "other" }))
    private val state = MutableStateFlow<List<SessionConfigOption>>(listOf(option("model", "small", "small", "large"),
        option("reasoning_effort", "low", "low", "medium"), option("mode", "ask", "ask", "unsafe")))
    private val native = mockk<ClientSession> {
        every { configOptionsSupported } returns true
        every { configOptions } returns state
        coEvery { setConfigOption(any(), any(), any()) } answers {
            val id = firstArg<String>()
            val value = secondArg<SessionConfigOptionValue.StringValue>().value
            state.value = if (id == "model") listOf(option("model", value, "small", "large"), option("reasoning_effort", "medium", "medium", "high"))
                else state.value.map { if (it.id.value == id) (it as SessionConfigOption.Select).copy(currentValue = SessionConfigValueId(value)) else it }
            SetSessionConfigOptionResponse(state.value)
        }
    }

    @Test fun `model change re-reads reasoning choices and confirms effective values`() = runTest {
        val before = controls.capabilities(native)
        assertEquals(listOf("low", "medium"), before.reasoning.map { it.value })
        val applied = controls.apply(native, KitModelSelection("large", "high"))
        assertEquals(KitModelSelection("large", "high"), applied.effective)
        assertNotEquals(before.version, applied.version)
        coVerifyOrder {
            native.setConfigOption(SessionConfigId("model"), SessionConfigOptionValue.StringValue("large"), null)
            native.setConfigOption(SessionConfigId("reasoning_effort"), SessionConfigOptionValue.StringValue("high"), null)
        }
        val medium = controls.apply(native, KitModelSelection(reasoning = "medium"))
        assertEquals(applied.version, medium.version)
    }

    @Test fun `missing capabilities support managed native defaults but reject explicit controls`() = runTest {
        every { native.configOptionsSupported } returns false
        assertNull(controls.apply(native, KitModelSelection()).effective.model)
        assertFailsWith<KitModelConfigurationException> { controls.apply(native, KitModelSelection("small")) }
        coVerify(exactly = 0) { native.setConfigOption(any(), any(), any()) }
    }

    @Test fun `unsupported reasoning after model change fails before a prompt can be submitted`() = runTest {
        assertFailsWith<KitModelConfigurationException> { controls.apply(native, KitModelSelection("large", "low")) }
        coVerify(exactly = 0) { native.setConfigOption(SessionConfigId("reasoning_effort"), any(), any()) }
    }

    @Test fun `unchanged values are idempotent and native refusal to confirm is a failure`() = runTest {
        controls.apply(native, KitModelSelection("small", "low"))
        coVerify(exactly = 0) { native.setConfigOption(any(), any(), any()) }
        coEvery { native.setConfigOption(any(), any(), any()) } returns SetSessionConfigOptionResponse(state.value)
        assertFailsWith<KitModelConfigurationException> { controls.apply(native, KitModelSelection("large")) }
    }

    @Test fun `ambiguous catalogs fail and unrelated native controls remain private`() = runTest {
        assertEquals(2, controls.capabilities(native).models.size)
        state.value = state.value + option("model", "small", "small")
        assertFailsWith<KitModelConfigurationException> { controls.capabilities(native) }
    }

    @Test fun `categories select opaque native identifiers without exposing unrelated controls`() = runTest {
        state.value = state.value.map { (it as SessionConfigOption.Select).copy(id = SessionConfigId("native-${it.id.value}")) }
        assertEquals(KitModelSelection("small", "low"), controls.capabilities(native).effective)
        coEvery { native.setConfigOption(SessionConfigId("native-reasoning_effort"), any(), any()) } answers {
            val value = secondArg<SessionConfigOptionValue.StringValue>().value
            state.value = state.value.map { if (it.id.value == "native-reasoning_effort") (it as SessionConfigOption.Select).copy(currentValue = SessionConfigValueId(value)) else it }
            SetSessionConfigOptionResponse(state.value)
        }
        assertEquals("medium", controls.apply(native, KitModelSelection(reasoning = "medium")).effective.reasoning)
        coVerify(exactly = 1) { native.setConfigOption(SessionConfigId("native-reasoning_effort"), any(), any()) }
    }
}
