package bosca.localization.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * The lifecycle stage of a translation within Bosca's localization workflow.
 *
 * Valid transitions are enforced by the localization service and recorded in the
 * translation history audit log. AI-produced translations start at [AI_GENERATED]
 * to force human review before publishing; human work typically starts at [DRAFT].
 *
 * Allowed transitions:
 * - [DRAFT] -> [IN_REVIEW]
 * - [AI_GENERATED] -> [IN_REVIEW]
 * - [IN_REVIEW] -> [APPROVED] | [REJECTED]
 * - [REJECTED] -> [DRAFT]
 * - [APPROVED] -> [PUBLISHED] | [DRAFT]
 * - [PUBLISHED] -> [ARCHIVED] | [DRAFT]
 * - [ARCHIVED] -> [DRAFT]
 */
@DbMapper(TranslationStateMapper::class)
@Serializable
enum class TranslationState {
    DRAFT,
    AI_GENERATED,
    IN_REVIEW,
    APPROVED,
    REJECTED,
    PUBLISHED,
    ARCHIVED
}

object TranslationStateMapper : EnumMapper<TranslationState>({ TranslationState.valueOf(it.uppercase()) })
