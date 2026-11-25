package bosca.ai.kit.harness.runtime

/** Archival retains local state and its previous checkpoint until storage capacity is available. */
class KitArchiveQuotaException : IllegalStateException(
    "Saved harness storage is full. Increase the per-person storage limit or delete an unused conversation, then retry archival.")
