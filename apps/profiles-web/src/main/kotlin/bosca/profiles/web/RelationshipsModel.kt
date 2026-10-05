package bosca.profiles.web

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.profiles.web.graphql.ApproveRelationship
import bosca.profiles.web.graphql.CancelRelationship
import bosca.profiles.web.graphql.DeclineRelationship
import bosca.profiles.web.graphql.RemoveRelationship
import bosca.profiles.web.graphql.RequestFriendship
import bosca.profiles.web.graphql.SearchFriendProfiles
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

private val relationshipLog = LoggerFactory.getLogger("bosca.profiles.web.RelationshipsModel")

@Serializable
class RelationshipsModel(
    val sourceProfileId: String,
    var relationships: List<RelationshipRow>,
    var incoming: List<RelationshipRequestRow>,
    var outgoing: List<RelationshipRequestRow>,
    var friendRequestOpen: Boolean = false,
    var friendSearchQuery: String = "",
    var friendSearchResults: List<ProfileSearchRow> = emptyList(),
    var selectedFriendId: String = "",
    var selectedFriendName: String = "",
    var friendSearchError: String? = null,
    var message: String? = null,
    var failed: Boolean = false,
    var messageId: Long = 0,
) {
    val statusClass: String get() = if (failed) "status error" else "status ok"
    val canSendFriendRequest: Boolean get() = selectedFriendId.isNotBlank()

    fun openFriendRequest() {
        friendRequestOpen = true
        friendSearchQuery = ""
        friendSearchResults = emptyList()
        selectedFriendId = ""
        selectedFriendName = ""
        friendSearchError = null
    }

    fun closeFriendRequest() {
        friendRequestOpen = false
        friendSearchResults = emptyList()
        friendSearchError = null
    }

    suspend fun searchFriends(query: String, ctx: RenderContext) {
        friendSearchQuery = query.trim()
        selectedFriendId = ""
        selectedFriendName = ""
        friendSearchError = null
        if (friendSearchQuery.length < 2) {
            friendSearchResults = emptyList()
            return
        }
        try {
            val unavailable = buildSet {
                add(sourceProfileId)
                relationships.forEach { add(it.profileId) }
                incoming.forEach { add(it.profileId) }
                outgoing.forEach { add(it.profileId) }
            }
            friendSearchResults = ctx.gql.execute(
                SearchFriendProfiles,
                SearchFriendProfiles.Variables(friendSearchQuery, 12, 0),
            ).search.search?.documents.orEmpty()
                .mapNotNull { it.profile }
                .filterNot { it.id in unavailable }
                .distinctBy { it.id }
                .map { profile ->
                    val avatarUrl = profile.attributes
                        .firstOrNull { it.typeId == "bosca.profiles.avatar" }
                        ?.attributes
                        ?.let { it as? JsonObject }
                        ?.get("picture")
                        ?.let { it as? JsonPrimitive }
                        ?.contentOrNull
                        .orEmpty()
                    ProfileSearchRow(profile.id, profile.name, profile.slug.orEmpty(), avatarUrl)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            relationshipLog.warn("Friend search failed for profile {}: {}", sourceProfileId, e.toString())
            friendSearchResults = emptyList()
            friendSearchError = "We couldn't search profiles. Please try again."
        }
    }

    fun selectFriend(profileId: String, name: String) {
        selectedFriendId = profileId
        selectedFriendName = name
        friendSearchResults = emptyList()
        friendSearchError = null
    }

    suspend fun sendFriendRequest(ctx: RenderContext) {
        if (!canSendFriendRequest) return
        try {
            val request = ctx.gql.execute(
                RequestFriendship,
                RequestFriendship.Variables(sourceProfileId, selectedFriendId),
            ).profiles.requestRelationship
            outgoing = outgoing + RelationshipRequestRow(
                id = request.id,
                profileId = request.target.id,
                name = request.target.name,
                slug = request.target.slug.orEmpty(),
                type = request.type,
                created = request.created,
            )
            friendRequestOpen = false
            friendSearchResults = emptyList()
            message = "Friend request sent."
            failed = false
            messageId += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            actionFailed("Friend request", "We couldn't send that friend request.", e)
        }
    }

    suspend fun approve(id: String, ctx: RenderContext) = mutate("approve", id, ctx)
    suspend fun decline(id: String, ctx: RenderContext) = mutate("decline", id, ctx)
    suspend fun cancel(id: String, ctx: RenderContext) = mutate("cancel", id, ctx)

    private suspend fun mutate(action: String, id: String, ctx: RenderContext) {
        try {
            val incomingRequest = incoming.firstOrNull { it.id == id }
            when (action) {
                "approve" -> ctx.gql.execute(ApproveRelationship, ApproveRelationship.Variables(id))
                "decline" -> ctx.gql.execute(DeclineRelationship, DeclineRelationship.Variables(id))
                else -> ctx.gql.execute(CancelRelationship, CancelRelationship.Variables(id))
            }
            incoming = incoming.filterNot { it.id == id }
            outgoing = outgoing.filterNot { it.id == id }
            if (action == "approve" && incomingRequest != null) {
                relationships = relationships + RelationshipRow(
                    incomingRequest.profileId,
                    incomingRequest.name,
                    incomingRequest.slug,
                    incomingRequest.type,
                )
            }
            message = when (action) {
                "approve" -> "Relationship request approved."
                "decline" -> "Relationship request declined."
                else -> "Relationship request canceled."
            }
            failed = false
            messageId += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            actionFailed("Relationship request update", "We couldn't update that relationship request.", e)
        }
    }

    suspend fun remove(targetId: String, type: String, ctx: RenderContext) {
        try {
            ctx.gql.execute(RemoveRelationship, RemoveRelationship.Variables(sourceProfileId, targetId, type))
            relationships = relationships.filterNot { it.profileId == targetId && it.type == type }
            message = "Relationship removed."
            failed = false
            messageId += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            actionFailed("Relationship removal", "We couldn't remove that relationship.", e)
        }
    }

    private fun actionFailed(operation: String, userMessage: String, error: Exception) {
        relationshipLog.warn("{} failed for profile {}: {}", operation, sourceProfileId, error.toString())
        message = userMessage
        failed = true
        messageId += 1
    }
}
