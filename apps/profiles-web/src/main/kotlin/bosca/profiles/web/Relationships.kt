package bosca.profiles.web

import bosca.bml.graphql.execute
import bosca.bml.render.client
import bosca.profiles.web.graphql.Relationships

suspend fun relationshipsModel(): RelationshipsModel {
    val profiles = client().execute(Relationships, Unit).profiles.current.orEmpty()
    val profile = profiles.firstOrNull { it.isPrimary } ?: profiles.firstOrNull()
        ?: error("No profile is associated with this account")
    return RelationshipsModel(
        sourceProfileId = profile.id,
        relationships = profile.relationships.map {
            RelationshipRow(it.profile.id, it.profile.name, it.profile.slug.orEmpty(), it.type)
        },
        incoming = profile.incomingRelationshipRequests.map {
            RelationshipRequestRow(
                it.id,
                it.requester.id,
                it.requester.name,
                it.requester.slug.orEmpty(),
                it.type,
                it.created,
            )
        },
        outgoing = profile.outgoingRelationshipRequests.map {
            RelationshipRequestRow(
                it.id,
                it.target.id,
                it.target.name,
                it.target.slug.orEmpty(),
                it.type,
                it.created,
            )
        },
    )
}
