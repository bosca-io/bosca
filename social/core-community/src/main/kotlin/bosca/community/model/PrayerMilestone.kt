package bosca.community.model

import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

object PrayerMilestone {

    private val FIXED_MILESTONES = listOf(
        "3_months" to 3L,
        "6_months" to 6L,
    )

    fun dueMilestones(answeredAt: OffsetDateTime, asOf: OffsetDateTime = OffsetDateTime.now()): List<String> {
        val milestones = mutableListOf<String>()
        val monthsSince = ChronoUnit.MONTHS.between(answeredAt, asOf)

        for ((label, months) in FIXED_MILESTONES) {
            if (monthsSince >= months) {
                milestones.add(label)
            }
        }

        if (monthsSince >= 12) {
            val yearsSince = (monthsSince / 12).toInt()
            for (year in 1..yearsSince) {
                milestones.add("${year}_year${if (year > 1) "s" else ""}")
            }
        }

        return milestones
    }
}
