package bosca.workops.model.artifact

import bosca.serialization.OffsetDateTime
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import java.sql.PreparedStatement
import java.sql.ResultSet
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppBuildPlatformTest {

    @Test
    fun `Android sequence is the version code through the Play limit`() {
        assertEquals("1", AppBuildPlatform.ANDROID.format(1))
        assertEquals("2100000000", AppBuildPlatform.ANDROID.format(2_100_000_000))
        assertEquals(42L, AppBuildPlatform.ANDROID.parse("42"))
        assertFailsWith<IllegalArgumentException> { AppBuildPlatform.ANDROID.format(0) }
        assertFailsWith<IllegalArgumentException> { AppBuildPlatform.ANDROID.format(2_100_000_001) }
        assertFailsWith<IllegalArgumentException> { AppBuildPlatform.ANDROID.parse("not-a-number") }
        assertFailsWith<IllegalArgumentException> { AppBuildPlatform.ANDROID.parse("0") }
        assertFailsWith<IllegalArgumentException> { AppBuildPlatform.ANDROID.parse("2100000001") }
    }

    @Test
    fun `iOS sequence is a plain integer through the single component Apple limit`() {
        assertEquals("1", AppBuildPlatform.IOS.format(1))
        assertEquals("9999", AppBuildPlatform.IOS.format(9_999))
        assertEquals(42L, AppBuildPlatform.IOS.parse("42"))
        assertFailsWith<IllegalArgumentException> { AppBuildPlatform.IOS.format(0) }
        assertFailsWith<IllegalArgumentException> { AppBuildPlatform.IOS.format(10_000) }
    }

    @Test
    fun `iOS migration floors reject non-integers and values outside the bundle limit`() {
        listOf("", "0", "1.0.0", "-1", "10000", "a").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { AppBuildPlatform.IOS.parse(value) }
        }
    }

    @Test
    fun `iOS version scope follows major minor while Android remains global`() {
        assertEquals("6.2", AppBuildPlatform.IOS.versionScope("6.2.0"))
        assertEquals("6.2", AppBuildPlatform.IOS.versionScope("v6.2.19-rc.1+45"))
        assertEquals("7.0", AppBuildPlatform.IOS.versionScope("7.0.0"))
        assertEquals("global", AppBuildPlatform.ANDROID.versionScope("not-semantic"))

        listOf("", "6", "6.2", "6.2.x", "refs/tags/6.2.0").forEach { version ->
            assertFailsWith<IllegalArgumentException>(version) {
                AppBuildPlatform.IOS.versionScope(version)
            }
        }
    }

    @Test
    fun `database mapper accepts either database case and binds nullable values`() {
        val result = mockk<ResultSet>()
        every { result.getString(1) } returns "ios"
        every { result.getString("platform") } returnsMany listOf("ANDROID", null)

        assertEquals(AppBuildPlatform.IOS, AppBuildPlatformMapper.map(enumType(), emptyList(), result, 1))
        assertEquals(
            AppBuildPlatform.ANDROID,
            AppBuildPlatformMapper.map(enumType(), emptyList(), result, "platform"),
        )
        assertNull(AppBuildPlatformMapper.map(enumType(), emptyList(), result, "platform"))

        val statement = mockk<PreparedStatement>(relaxed = true)
        assertEquals(statement, AppBuildPlatformMapper.bind(enumType(), emptyList(), statement, 2, AppBuildPlatform.IOS))
        assertEquals(statement, AppBuildPlatformMapper.bind(enumType(), emptyList(), statement, 3, null))
        verify { statement.setString(2, "ios") }
        verify { statement.setNull(3, java.sql.Types.VARCHAR) }
    }

    @Test
    fun `allocation inputs expose stable defaults and immutable result identity`() {
        val repositoryId = UUID.random()
        val pipelineRunId = UUID.random()
        val input = AllocateAppBuildNumberInput(
            platform = AppBuildPlatform.ANDROID,
            applicationId = "io.bosca.app",
            repositoryId = repositoryId,
            sourceCommitSha = "a".repeat(40),
            sourceVersion = "6.2.0",
            pipelineRunId = pipelineRunId,
        )
        assertEquals("default", input.buildKey)
        assertEquals(1, input.minimumNumber)

        val allocation = AppBuildNumberAllocation(
            platform = input.platform,
            applicationId = input.applicationId,
            repositoryId = repositoryId,
            sourceCommitSha = input.sourceCommitSha,
            sourceVersion = input.sourceVersion,
            pipelineRunId = pipelineRunId,
            number = 1,
            value = "1",
        )
        assertEquals(UUID.NIL, allocation.id)
        assertEquals("default", allocation.buildKey)
        assertEquals(allocation, AppBuildNumberAllocationResult(allocation, reused = true).allocation)
    }

    @Test
    fun `allocation serialization round trips defaults and rejects every missing required field`() {
        val repositoryId = UUID.random()
        val pipelineRunId = UUID.random()
        val allocation = AppBuildNumberAllocation(
            id = UUID.random(),
            platform = AppBuildPlatform.IOS,
            applicationId = "com.example.app",
            versionScope = "6.2",
            buildKey = "release",
            repositoryId = repositoryId,
            sourceCommitSha = "a".repeat(40),
            sourceVersion = "6.2.0",
            pipelineRunId = pipelineRunId,
            number = 1,
            value = "1",
            createdAt = OffsetDateTime.parse("2026-07-21T12:00:00Z"),
        )
        val json = Json {
            encodeDefaults = true
            serializersModule = SerializersModule {
                contextual(UUID::class, UUIDSerializer())
                contextual(OffsetDateTime::class, OffsetDateTimeSerializer())
            }
        }
        val encoded = json.encodeToString(allocation)
        val decoded = json.decodeFromString<AppBuildNumberAllocation>(encoded)

        assertEquals(allocation.id, decoded.id)
        assertEquals(allocation.platform, decoded.platform)
        assertEquals(allocation.applicationId, decoded.applicationId)
        assertEquals(allocation.versionScope, decoded.versionScope)
        assertEquals(allocation.buildKey, decoded.buildKey)
        assertEquals(allocation.repositoryId, decoded.repositoryId)
        assertEquals(allocation.sourceCommitSha, decoded.sourceCommitSha)
        assertEquals(allocation.sourceVersion, decoded.sourceVersion)
        assertEquals(allocation.pipelineRunId, decoded.pipelineRunId)
        assertEquals(allocation.number, decoded.number)
        assertEquals(allocation.value, decoded.value)
        assertEquals(allocation.createdAt, decoded.createdAt)

        val encodedObject = json.parseToJsonElement(encoded).jsonObject
        val withoutDefaults = JsonObject(encodedObject - setOf("id", "buildKey", "createdAt"))
        val defaulted = json.decodeFromString<AppBuildNumberAllocation>(withoutDefaults.toString())
        assertEquals(UUID.NIL, defaulted.id)
        assertEquals("default", defaulted.buildKey)

        val compactJson = Json {
            encodeDefaults = false
            serializersModule = json.serializersModule
        }
        val compactDefaults = compactJson.parseToJsonElement(
            compactJson.encodeToString(
                allocation.copy(id = UUID.NIL, buildKey = "default", createdAt = OffsetDateTime.now()),
            ),
        ).jsonObject
        assertFalse("id" in compactDefaults)
        assertFalse("buildKey" in compactDefaults)
        val compactExplicit = compactJson.parseToJsonElement(compactJson.encodeToString(allocation)).jsonObject
        assertEquals(allocation.id.toString(), compactExplicit.getValue("id").jsonPrimitive.content)
        assertEquals("release", compactExplicit.getValue("buildKey").jsonPrimitive.content)
        assertEquals("6.2", compactExplicit.getValue("versionScope").jsonPrimitive.content)

        listOf(
            "platform", "applicationId", "repositoryId", "sourceCommitSha",
            "sourceVersion", "pipelineRunId", "number", "value",
        ).forEach { requiredField ->
            val missing = JsonObject(encodedObject - requiredField)
            assertFailsWith<SerializationException>(requiredField) {
                json.decodeFromString<AppBuildNumberAllocation>(missing.toString())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun enumType(): KClass<*> = AppBuildPlatform::class
}
