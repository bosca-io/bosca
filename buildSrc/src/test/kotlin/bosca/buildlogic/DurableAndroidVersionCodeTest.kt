package bosca.buildlogic

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DurableAndroidVersionCodeTest {

    @Test
    fun `debug builds default to one while supplied allocations are preserved`() {
        assertEquals(1, resolveDurableAndroidVersionCode(null, listOf(":app:android:assembleDebug")))
        assertEquals(1, resolveDurableAndroidVersionCode("1", listOf(":app:android:assembleRelease")))
        assertEquals(
            2_100_000_000,
            resolveDurableAndroidVersionCode("2100000000", listOf(":app:android:bundleRELEASE")),
        )
    }

    @Test
    fun `every explicitly requested release task requires a durable allocation`() {
        listOf("assembleRelease", ":app:android:bundleRELEASE", "publishReleaseBundle").forEach { task ->
            val failure = assertFailsWith<IllegalStateException>(task) {
                resolveDurableAndroidVersionCode(null, listOf(task))
            }
            assertTrue("uses: allocate-build-number" in (failure.message ?: ""))
        }
    }

    @Test
    fun `invalid environment values fail configuration`() {
        listOf("", "abc", "0", "-1", "2100000001").forEach { value ->
            val failure = assertFailsWith<IllegalStateException>(value) {
                resolveDurableAndroidVersionCode(value, emptyList())
            }
            assertTrue("between 1 and 2100000000" in (failure.message ?: ""))
        }
    }

    @Test
    fun `release task detection is case insensitive and ignores debug tasks`() {
        assertTrue(isAndroidReleaseTask("compileReleaseKotlin"))
        assertTrue(isAndroidReleaseTask("bundleRELEASE"))
        assertTrue(isAndroidReleaseTask(":app:android:assembleRelease"))
        assertEquals(false, isAndroidReleaseTask("assembleDebug"))
        assertEquals(false, isAndroidReleaseTask(":app:compose:packageReleaseDistributionForCurrentOS"))
        assertEquals(false, isAndroidReleaseTask(":other:assembleRelease"))
    }

    @Test
    fun `non Android release tasks do not require an Android allocation`() {
        assertEquals(
            1,
            resolveDurableAndroidVersionCode(
                null,
                listOf(":app:compose:packageReleaseDistributionForCurrentOS"),
            ),
        )
    }

    @Test
    fun `validation task requires and accepts a durable allocation`() {
        val task = ProjectBuilder.builder().build().tasks.register(
            "validateDurableAndroidVersionCode",
            ValidateDurableAndroidVersionCode::class.java,
        ).get()
        assertFailsWith<IllegalStateException> { task.validate() }
        task.versionCode.set("42")
        task.validate()
    }
}
