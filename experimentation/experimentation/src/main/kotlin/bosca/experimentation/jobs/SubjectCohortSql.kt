package bosca.experimentation.jobs

internal data class SubjectCohortSql(
    val ctes: String,
    val params: List<String>,
    val hasActivation: Boolean,
)
