package bosca.community.graphql

import bosca.community.model.CommunityActivity
import bosca.community.model.Prayer
import bosca.community.service.CommunityActivityService
import bosca.community.service.PrayerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.serialization.UUID

/**
 * Adds community-specific resolvers to the [MessageContent] GraphQL type.
 * The base resolvers (type, content, attributes, metadata) live in the chat
 * module; this controller contributes prayer and activity resolution for
 * content blocks of type PRAYER and ACTIVITY.
 */
@TypeController
class CommunityMessageContentExtension(
    private val prayerService: PrayerService,
    private val communityActivityService: CommunityActivityService,
) : GraphQLController<MessageContent> {

    @Field
    suspend fun prayer(content: MessageContent): Prayer? {
        if (content.type != MessageContentType.PRAYER) return null
        return try {
            prayerService.getRequest(UUID.parse(content.content))
        } catch (_: Exception) {
            null
        }
    }

    @Field
    suspend fun activity(content: MessageContent): CommunityActivity? {
        if (content.type != MessageContentType.ACTIVITY) return null
        return try {
            communityActivityService.getActivity(UUID.parse(content.content))
        } catch (_: Exception) {
            null
        }
    }
}
