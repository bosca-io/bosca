package bosca.analytics.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class AnalyticsQueryParameterDateTest {

    @Test
    fun `AnalyticsQueryParameterDate default values`() {
        val date = AnalyticsQueryParameterDate()
        assertNull(date.value)
        assertFalse(date.now)
        assertNull(date.nowDayOffset)
    }

    @Test
    fun `AnalyticsQueryParameterDate with now flag`() {
        val date = AnalyticsQueryParameterDate(now = true)
        assertTrue(date.now)
        assertNull(date.value)
        assertNull(date.nowDayOffset)
    }

    @Test
    fun `AnalyticsQueryParameterDate with day offset`() {
        val date = AnalyticsQueryParameterDate(now = true, nowDayOffset = -7)
        assertTrue(date.now)
        assertEquals(-7, date.nowDayOffset)
    }

    @Test
    fun `AnalyticsQueryParameterDate with positive day offset`() {
        val date = AnalyticsQueryParameterDate(now = true, nowDayOffset = 30)
        assertEquals(30, date.nowDayOffset)
    }

    @Test
    fun `AnalyticsQueryParameterDate with zero day offset`() {
        val date = AnalyticsQueryParameterDate(now = true, nowDayOffset = 0)
        assertEquals(0, date.nowDayOffset)
    }

    @Test
    fun `AnalyticsQueryParameterDate equality`() {
        val a = AnalyticsQueryParameterDate(now = true, nowDayOffset = -1)
        val b = AnalyticsQueryParameterDate(now = true, nowDayOffset = -1)
        assertEquals(a, b)
    }

    @Test
    fun `AnalyticsQueryParameterDate inequality`() {
        val a = AnalyticsQueryParameterDate(now = true, nowDayOffset = -1)
        val b = AnalyticsQueryParameterDate(now = false, nowDayOffset = -1)
        assertNotEquals(a, b)
    }

    @Test
    fun `AnalyticsQueryParameterDate copy`() {
        val original = AnalyticsQueryParameterDate(now = true, nowDayOffset = 5)
        val copy = original.copy(nowDayOffset = 10)
        assertEquals(10, copy.nowDayOffset)
        assertTrue(copy.now)
    }
}
