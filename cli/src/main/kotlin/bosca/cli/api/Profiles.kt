package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.AddProfileAttributeType
import bosca.graphql.gen.AddProfileAttributes
import bosca.graphql.gen.AddProfileCollection
import bosca.graphql.gen.EditProfileAttributeType
import bosca.graphql.gen.GetProfileAttributeTypes
import bosca.graphql.gen.GetProfileAttributeTypesData
import bosca.graphql.gen.GetProfileSlug
import bosca.graphql.gen.GetProfiles
import bosca.graphql.gen.GetProfilesData
import bosca.graphql.gen.ProfileAttributeInput
import bosca.graphql.gen.ProfileAttributeTypeInput
import kotlin.uuid.Uuid

class Profiles(network: NetworkClient) : Api(network) {

    suspend fun getAll(offset: Long, limit: Int): List<Pair<GetProfilesData.Profiles.All, GetProfilesData.Profiles.All.Principal?>> =
        network.boscaGraphql.execute(GetProfiles, GetProfiles.Variables(offset, limit))
            .profiles.all.map { Pair(it, it.principal) }

    /**
     * Resolves a profile's URL-friendly slug by its id. Returns null when the
     * profile does not exist or the caller lacks permission to see it — the
     * server returns null rather than an error in both cases.
     */
    suspend fun getSlug(id: Uuid): String? =
        network.boscaGraphql.execute(GetProfileSlug, GetProfileSlug.Variables(id)).profiles.profile?.slug

    suspend fun getAttributeTypes(): List<GetProfileAttributeTypesData.Profiles.AttributeTypes.All> =
        network.boscaGraphql.execute(GetProfileAttributeTypes, Unit).profiles.attributeTypes.all

    suspend fun addAttributeType(type: ProfileAttributeTypeInput) {
        network.boscaGraphql.execute(AddProfileAttributeType, AddProfileAttributeType.Variables(type))
    }

    suspend fun editAttributeType(type: ProfileAttributeTypeInput) {
        network.boscaGraphql.execute(EditProfileAttributeType, EditProfileAttributeType.Variables(type))
    }

    suspend fun addAttributes(id: Uuid, attributes: List<ProfileAttributeInput>) {
        network.boscaGraphql.execute(AddProfileAttributes, AddProfileAttributes.Variables(id, attributes))
    }

    suspend fun addCollection(id: Uuid) {
        network.boscaGraphql.execute(AddProfileCollection, AddProfileCollection.Variables(id))
    }
}
