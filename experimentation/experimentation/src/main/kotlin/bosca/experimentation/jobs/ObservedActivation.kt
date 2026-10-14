package bosca.experimentation.jobs

internal data class ObservedActivation(
    val subject: ActivatedSubject, val events: Long,
)
