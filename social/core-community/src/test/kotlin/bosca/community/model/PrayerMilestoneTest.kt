package bosca.community.model

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrayerMilestoneTest {

    @Test
    fun `no milestones before 3 months`() {
        val answeredAt = OffsetDateTime.now().minusMonths(2)
        val milestones = PrayerMilestone.dueMilestones(answeredAt)
        assertTrue(milestones.isEmpty())
    }

    @Test
    fun `3 month milestone at 3 months`() {
        val answeredAt = OffsetDateTime.now().minusMonths(3)
        val milestones = PrayerMilestone.dueMilestones(answeredAt)
        assertEquals(listOf("3_months"), milestones)
    }

    @Test
    fun `3 and 6 month milestones at 6 months`() {
        val answeredAt = OffsetDateTime.now().minusMonths(6)
        val milestones = PrayerMilestone.dueMilestones(answeredAt)
        assertEquals(listOf("3_months", "6_months"), milestones)
    }

    @Test
    fun `includes 1 year milestone at 12 months`() {
        val answeredAt = OffsetDateTime.now().minusMonths(12)
        val milestones = PrayerMilestone.dueMilestones(answeredAt)
        assertEquals(listOf("3_months", "6_months", "1_year"), milestones)
    }

    @Test
    fun `includes multiple year milestones`() {
        val answeredAt = OffsetDateTime.now().minusMonths(36)
        val milestones = PrayerMilestone.dueMilestones(answeredAt)
        assertEquals(listOf("3_months", "6_months", "1_year", "2_years", "3_years"), milestones)
    }

    @Test
    fun `5 months yields only 3 month milestone`() {
        val answeredAt = OffsetDateTime.now().minusMonths(5)
        val milestones = PrayerMilestone.dueMilestones(answeredAt)
        assertEquals(listOf("3_months"), milestones)
    }

    @Test
    fun `11 months yields 3 and 6 month milestones`() {
        val answeredAt = OffsetDateTime.now().minusMonths(11)
        val milestones = PrayerMilestone.dueMilestones(answeredAt)
        assertEquals(listOf("3_months", "6_months"), milestones)
    }
}
