package bosca.collaboration.service

import bosca.collaboration.model.MentionValidationResult
import bosca.communications.model.MessageContent
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Extracts and validates @-mentions from chat message content. Mentions can
 * appear as structured [bosca.communications.model.MessageContentType.MENTION] content
 * blocks, UUID tokens, canonical `@profile-slug` tokens, or exact `@Profile Name` tokens in text
 * content. Validation checks whether mentioned profiles are reachable as channel members or active
 * chat participants.
 */
interface MentionService : Service {

    /**
     * Scans message content for mention references and returns the unique
     * profile IDs of all mentioned users.
     *
     * @param content the list of message content blocks to scan
     * @return the set of mentioned profile IDs
     */
    suspend fun extractMentions(content: List<MessageContent>): List<UUID>

    /**
     * Validates a set of mentioned profile IDs against a channel's membership
     * and general chat participation eligibility, categorizing each mention by reachability.
     *
     * @param channelId the channel the message was sent in
     * @param profileIds the profile IDs extracted from mentions
     * @return a [MentionValidationResult] categorizing each mentioned profile
     */
    suspend fun validateMentions(channelId: UUID, profileIds: List<UUID>): MentionValidationResult
}
