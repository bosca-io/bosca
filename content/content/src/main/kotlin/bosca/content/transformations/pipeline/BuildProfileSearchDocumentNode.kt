package bosca.content.transformations.pipeline

import bosca.content.transformations.ProfileToSearchDocument
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import bosca.profile.model.Profile
import bosca.search.IndexStorageSystem
import bosca.search.model.ProfileSearchContext
import bosca.search.pipeline.SearchDocumentPipeline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Builds the search document for a [Profile] and a target index — [ProfileToSearchDocument.toContext]
 * (which resolves the profile's organization, member count, attributes, and slug) then a configurable
 * JSONata expression. Output is JSON you can manipulate before indexing.
 *
 * - [expression] blank → the platform's configured `search` profile expression.
 * - [index] selects the target index.
 * - The visibility toggles and the optional [indexWhen] predicate decide *whether* to index; a gated-out
 *   profile emits a removal signal so the Index Document node removes any stale document. Turn the
 *   public requirement off for an Admin index branch.
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "Build Profile Search Document",
    description = "Builds a Profile's search document as JSON, applying a configurable JSONata expression. Pair with Index Document to index it.",
    group = "Content",
    subgroup = "Search Documents",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Profile",
            type = Profile::class,
            description = "A Profile (e.g. from Get Profile) to build a search document for.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            typeLabel = "Search document (JSON)",
            description = "The profile's search document as JSON, or a removal signal when it is not visible for the target index.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "expression", control = SettingControl.CODE, language = "jsonata", label = "Search document expression (JSONata)",
            placeholder = "Leave blank to use the platform's default expression for this type",
            description = "Builds the search document from the entity, then applies this expression (blank uses the platform default). Output is JSON — send it to an Index Document node, or reshape it with a JSONata node first.",
        ),
        SettingSlot(
            name = "index", control = SettingControl.REFERENCE, reference = ReferenceSource.SEARCH_INDEX, label = "Target index",
            placeholder = "Profile Search Index",
        ),
        SettingSlot(name = "requirePublic", control = SettingControl.BOOLEAN, label = "Public", default = "true", group = "Index only when"),
        SettingSlot(name = "requirePublished", control = SettingControl.BOOLEAN, label = "Published", default = "true", group = "Index only when"),
        SettingSlot(name = "requireSearchable", control = SettingControl.BOOLEAN, label = "Searchable", default = "true", group = "Index only when"),
        SettingSlot(name = "excludeDeleted", control = SettingControl.BOOLEAN, label = "Not deleted", default = "true", group = "Index only when"),
        SettingSlot(
            name = "indexWhen", control = SettingControl.TEXT, label = "Extra constraint (JSONata, optional)", mono = true,
            placeholder = "e.g. attributes.indexable = true",
        ),
    ],
)
@Serializable
@SerialName("search.buildProfileDocument")
class BuildProfileSearchDocumentNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** JSONata applied to the built context; blank uses the platform's configured profile expression. */
    val expression: String = "",
    /** The target search index name. */
    val index: String = SearchDocumentPipeline.PROFILE_INDEX,
    /** Only index when the profile is public. */
    val requirePublic: Boolean = true,
    /** Only index when the profile is published (a profile is published unless deleted). */
    val requirePublished: Boolean = true,
    /** Only index when the profile owner has enabled search and the profile is not deleted. */
    val requireSearchable: Boolean = true,
    /** Never index a deleted profile. */
    val excludeDeleted: Boolean = true,
    /** Optional JSONata predicate over the profile; when set and false, the profile is not indexed. */
    val indexWhen: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val profile = BuildProfileSearchDocumentNodeSerializer.deserialize(context, inputs).`in`
        val visible = ContentVisibility.passes(
            public = profile.public, published = profile.isPublished,
            searchable = profile.isSearchable, deleted = profile.isDeleted,
            requirePublic = requirePublic, requirePublished = requirePublished,
            requireSearchable = requireSearchable, excludeDeleted = excludeDeleted,
        ) && (indexWhen.isBlank() || ContentVisibility.matches(
            indexWhen, context.json.encodeToJsonElement(Profile.serializer(), profile),
        ))
        if (!visible) return PipelineValue.ofJson(SearchDocumentBuild.removalSignal(profile.id.toString()))
        val system = IndexStorageSystem(name = index)
        val searchContext = provide<ProfileToSearchDocument>().toContext(system, profile)
        val element = context.json.encodeToJsonElement(ProfileSearchContext.serializer(), searchContext)
        return PipelineValue.ofJson(SearchDocumentBuild.shape(context, element, expression) { it.profile })
    }
}
