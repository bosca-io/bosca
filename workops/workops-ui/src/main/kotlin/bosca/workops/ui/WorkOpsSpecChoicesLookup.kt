package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.workops.ui.generated.WorkOpsSpecChoices
import bosca.workops.ui.graphql.WorkOpsSpecChoiceByMetadata
import bosca.workops.ui.graphql.WorkOpsSpecContentChoices
import kotlinx.serialization.json.*

/** Resolves title search through Content and specification VIEW without assuming a template's mutable type attribute. */
class WorkOpsSpecChoicesLookup : WorkOpsSpecChoices {
    override suspend fun search(gql: GraphQLClient, query: String, offset: Int): Map<String, String> {
        require(query.trim().length >= 2 && offset >= 0) { "Enter at least two characters and a valid offset." }
        val documents = gql.execute(WorkOpsSpecContentChoices, WorkOpsSpecContentChoices.Variables(query.trim(), offset)).search.search?.documents.orEmpty()
        val options = buildJsonArray {
            for (metadata in documents.take(25).mapNotNull { it.metadata }.distinctBy { it.id }) {
                val spec = gql.execute(WorkOpsSpecChoiceByMetadata, WorkOpsSpecChoiceByMetadata.Variables(metadata.id)).workOps.specs.specByMetadataId ?: continue
                val visible = spec.metadata ?: continue
                addJsonObject {
                    put("value", spec.id)
                    put("label", workOpsContentLabel(visible.name, spec.metadataId, "Specification") + spec.project?.name?.let { " · $it" }.orEmpty())
                }
            }
        }
        return buildMap {
            put("options", options.toString())
            if (offset > 0) put("previousOffset", (offset - 25).coerceAtLeast(0).toString())
            if (documents.size > 25) put("nextOffset", (offset + 25).toString())
        }
    }
}
