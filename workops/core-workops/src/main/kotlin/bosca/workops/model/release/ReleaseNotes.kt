package bosca.workops.model.release

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Auto-generated or manually edited release notes for a [Release].
 * Sections are aggregated from tasks whose [fixVersionIds] reference
 * the versions bundled in the release, categorized by label
 * convention.
 */
@Serializable
data class ReleaseNotes(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("release_id")
    @Contextual
    val releaseId: UUID,
    @ColumnName("generated_at")
    @Contextual
    val generatedAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("manually_edited")
    val manuallyEdited: Boolean = false,
    @Contextual
    val sections: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonArray(emptyList()),
    val version: Long = 0,
)

/**
 * Store-facing release notes owned by one WorkOps Version and assembled from the localization
 * subsystem's authoritative strings and translations. This is a read model, not a persisted WorkOps row.
 */
@Serializable
data class VersionReleaseNotes(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    val versionId: UUID,
    val sourceLocale: String,
    @Contextual
    val generatedAt: OffsetDateTime? = null,
    val manuallyEdited: Boolean = false,
    @Contextual
    val variants: JsonElement,
)

/** The three store fields generated and localized for one BCP-47 [locale]. */
@Serializable
data class LocalizedReleaseNotes(
    val locale: String,
    val playReleaseNotes: String,
    val appStoreWhatsNew: String,
    val testFlightWhatToTest: String,
)

/** A bounded commit summary supplied to Kit when drafting version release notes. */
@Serializable
data class ReleaseNotesCommit(
    val repository: String,
    val sha: String,
    val message: String,
)

/** A bounded diff summary supplied to Kit when drafting version release notes. */
@Serializable
data class ReleaseNotesChange(
    val repository: String,
    val path: String,
    val changeType: String,
    val additions: List<String> = emptyList(),
    val deletions: List<String> = emptyList(),
)

/** Structured input for Kit's release-notes specialist. */
@Serializable
data class ReleaseNotesGenerationInput(
    val projectName: String,
    val versionName: String,
    val previousVersionName: String? = null,
    val sourceLocale: String,
    val commits: List<ReleaseNotesCommit>,
    val changes: List<ReleaseNotesChange>,
)

@Serializable
data class ReleaseNotesSection(
    val category: ReleaseNotesCategory,
    val entries: List<ReleaseNotesEntry>,
)

@Serializable
data class ReleaseNotesEntry(
    @Contextual
    val taskId: UUID,
    val taskKey: String,
    val summary: String,
    val projectName: String,
)

@Serializable
enum class ReleaseNotesCategory {
    BREAKING_CHANGE,
    NEW_FEATURE,
    ENHANCEMENT,
    BUG_FIX,
    DEPRECATION,
    SECURITY,
    PERFORMANCE,
    DOCUMENTATION,
    OTHER,
}
