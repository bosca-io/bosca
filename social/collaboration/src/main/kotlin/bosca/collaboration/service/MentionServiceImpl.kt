package bosca.collaboration.service

import bosca.chat.service.ChatService
import bosca.collaboration.model.MentionValidationResult
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Parses mentions from message content blocks and validates them against
 * channel membership and general chat participation eligibility.
 */
@ServiceImplementation
class MentionServiceImpl(
    private val chatService: ChatService,
    private val profileService: ProfileService,
) : MentionService {

    private val profileIdMentionPattern = Regex("@\\{([0-9a-fA-F-]{36})}|@([0-9a-fA-F-]{36})")
    private val profileTextMentionPattern = Regex(
        pattern = "(?<![\\p{L}\\p{N}_@])@([\\p{L}\\p{N}](?:[\\p{L}\\p{N}_-]*[\\p{L}\\p{N}])?)(?![\\p{L}\\p{N}_-])",
        option = RegexOption.IGNORE_CASE,
    )

    override suspend fun extractMentions(content: List<MessageContent>): List<UUID> {
        val mentions = mutableSetOf<UUID>()
        val textMentions = mutableListOf<TextMention>()
        for (block in content) {
            when (block.type) {
                MessageContentType.MENTION -> {
                    runCatching { UUID.parse(block.content) }.getOrNull()?.let { mentions.add(it) }
                }
                MessageContentType.TEXT, MessageContentType.HTML -> {
                    profileIdMentionPattern.findAll(block.content).forEach { match ->
                        val uuidStr = match.groupValues[1].ifEmpty { match.groupValues[2] }
                        runCatching { UUID.parse(uuidStr) }.getOrNull()?.let { mentions.add(it) }
                    }
                    profileTextMentionPattern.findAll(block.content).forEach { match ->
                        val slug = match.groupValues[1].lowercase()
                        if (runCatching { UUID.parse(slug) }.isFailure) {
                            textMentions.add(
                                TextMention(
                                    slug = slug,
                                    nameCandidates = profileNameCandidates(block.content, match.range.first),
                                ),
                            )
                        }
                    }
                }
                else -> {}
            }
        }
        resolveTextMentions(textMentions, mentions)
        return mentions.toList()
    }

    private suspend fun resolveTextMentions(textMentions: List<TextMention>, mentions: MutableSet<UUID>) {
        if (textMentions.isEmpty()) return

        val profilesByName = profileService.getByNames(
            textMentions.flatMap { it.nameCandidates }.distinct(),
        ).asSequence()
            .filter { !it.isDeleted && it.principal != null }
            .groupBy { normalizeProfileName(it.name) }
        val profilesBySlug = mutableMapOf<String, Profile?>()

        for (textMention in textMentions) {
            val multiWordMatch = textMention.nameCandidates
                .asReversed()
                .firstNotNullOfOrNull { candidate ->
                    candidate.takeIf { ' ' in it }
                        ?.let { profilesByName[normalizeProfileName(it)] }
                        ?.takeIf { it.isNotEmpty() }
                }
            if (multiWordMatch != null) {
                multiWordMatch.singleOrNull()?.let { mentions.add(it.id) }
                continue
            }

            val slugProfile = if (profilesBySlug.containsKey(textMention.slug)) {
                profilesBySlug[textMention.slug]
            } else {
                profileService.getBySlug(textMention.slug).also {
                    profilesBySlug[textMention.slug] = it
                }
            }
            if (slugProfile != null) {
                mentions.add(slugProfile.id)
                continue
            }

            val singleNameMatch = textMention.nameCandidates.firstOrNull()
                ?.let { profilesByName[normalizeProfileName(it)] }
            singleNameMatch?.singleOrNull()?.let { mentions.add(it.id) }
        }
    }

    private fun profileNameCandidates(content: String, mentionStart: Int): List<String> {
        val segment = content.substring(mentionStart + 1)
            .takeWhile { it != '@' && it != '\r' && it != '\n' && it != '<' }
            .take(MAX_PROFILE_NAME_SCAN_LENGTH)
        val words = nonWhitespacePattern.findAll(segment)
            .take(MAX_PROFILE_NAME_WORDS)
            .map { it.value }
            .toList()
        return words.indices.mapNotNull { index ->
            words.take(index + 1)
                .joinToString(" ")
                .trimEnd { it in trailingMentionPunctuation }
                .takeIf { it.isNotEmpty() }
        }
    }

    private fun normalizeProfileName(name: String): String =
        nonWhitespacePattern.findAll(name.trim()).joinToString(" ") { it.value }.lowercase()

    override suspend fun validateMentions(channelId: UUID, profileIds: List<UUID>): MentionValidationResult {
        if (profileIds.isEmpty()) {
            return MentionValidationResult(emptyList(), emptyList(), emptyList())
        }
        val requestedProfileIds = profileIds.distinct()
        require(requestedProfileIds.size <= MAX_MENTIONS) {
            "a message cannot mention more than $MAX_MENTIONS profiles"
        }

        val memberIds = chatService.getMembers(channelId, requestedProfileIds)
            .mapTo(mutableSetOf()) { it.profileId }
        val nonMemberIds = requestedProfileIds.filterNot { it in memberIds }
        val profilesById = if (nonMemberIds.isEmpty()) {
            emptyMap()
        } else {
            profileService.getAllByIds(nonMemberIds).associateBy { it.id }
        }

        val channelMemberIds = mutableListOf<UUID>()
        val reachableNonMemberIds = mutableListOf<UUID>()
        val unreachableIds = mutableListOf<UUID>()

        for (profileId in requestedProfileIds) {
            when {
                profileId in memberIds -> channelMemberIds.add(profileId)
                profilesById[profileId] != null && chatService.canParticipate(profileId) ->
                    reachableNonMemberIds.add(profileId)
                else -> unreachableIds.add(profileId)
            }
        }

        return MentionValidationResult(
            channelMembers = channelMemberIds,
            reachableNonMembers = reachableNonMemberIds,
            unreachable = unreachableIds
        )
    }

    internal companion object {
        const val MAX_MENTIONS = 100
        private const val MAX_PROFILE_NAME_WORDS = 8
        private const val MAX_PROFILE_NAME_SCAN_LENGTH = 160
        private val nonWhitespacePattern = Regex("\\S+")
        private val trailingMentionPunctuation = setOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '\"', '\'')
    }

    private data class TextMention(
        val slug: String,
        val nameCandidates: List<String>,
    )
}
