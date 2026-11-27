package bosca.workops.ui

/** Generated WorkOps tracking keys are not document display names. Authored names remain intact. */
fun workOpsContentLabel(name: String, id: String, fallback: String = "Untitled content"): String = when {
    name.isBlank() || name == id -> fallback
    name.matches(Regex("[A-Z][A-Z0-9_]{1,9}-SPEC-[0-9]+")) -> "Specification"
    name.matches(Regex("[A-Z][A-Z0-9_]{1,9}-REQ-[0-9]+")) -> "Requirement"
    name.matches(Regex("REQ [A-Z][A-Z0-9_]{1,9}-REQ-[0-9]+")) -> "Requirement"
    else -> name
}
