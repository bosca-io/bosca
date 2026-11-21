package bosca.workops.ui

/** A visible collection child linked to its authorized Atlas reference view. */
data class WorkOpsReferenceItem(val kind: String, val id: String, val label: String, val returnTo: String) {
    val kindLabel get() = if (kind == "collection") "Collection" else "Content"
    val href get() = workOpsReferenceHref(kind, id, returnTo)
}
