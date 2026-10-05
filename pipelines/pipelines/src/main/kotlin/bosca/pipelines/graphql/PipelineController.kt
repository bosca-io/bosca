@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pipelines.model.BrokenPipeline
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.inputNode
import bosca.pipelines.model.outputNode
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.ShapeField
import bosca.pipelines.security.PipelinePermissionEvaluator
import bosca.pipelines.service.PipelineService
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/** Field wiring for the `Pipeline` GraphQL type; source is the [Pipeline] model. */
@TypeController
class PipelineController(
    private val service: PipelineService,
    private val permissionEvaluator: PipelinePermissionEvaluator,
) : GraphQLController<Pipeline> {

    @Field
    fun id(source: Pipeline): UUID = source.id

    @Field
    fun key(source: Pipeline): String = source.key

    @Field
    fun api(source: Pipeline): Boolean = source.api

    @Field
    fun public(source: Pipeline): Boolean = source.public

    @Field
    fun schedule(source: Pipeline): String? = source.schedule

    @Field
    fun maxConcurrentRuns(source: Pipeline): Int? = source.maxConcurrentRuns

    @Field
    fun maxRunsPerMinute(source: Pipeline): Int? = source.maxRunsPerMinute

    @Field
    suspend fun permissions(authentication: AuthenticationContext?, source: Pipeline): List<Permission> {
        if (!permissionEvaluator.isAllowed(authentication, source, PermissionAction.MANAGE)) {
            return emptyList()
        }
        return service.getPermissions(source).map { Permission(it.groupId, it.action) }
    }

    @Field
    fun name(source: Pipeline): String = source.name

    @Field
    fun description(source: Pipeline): String = source.description

    @Field
    fun acceptedInputType(source: Pipeline): String = source.acceptedInputType

    @Field
    fun tags(source: Pipeline): List<String> = source.tags

    @Field
    fun triggered(source: Pipeline): Boolean = source.triggered

    @Field
    fun version(source: Pipeline): Int = source.version.toInt()

    @Field
    suspend fun graph(source: Pipeline): JsonElement = service.graphAsJsonElement(source)

    @Field
    fun gitRepositoryId(source: Pipeline): UUID? = source.gitRepositoryId

    @Field
    fun gitPath(source: Pipeline): String? = source.gitPath

    @Field
    fun lastSyncError(source: Pipeline): String? = source.lastSyncError

    // The input/output contract lives on the graph's Input/Output nodes (single source of truth);
    // these projections let pickers and lists show it without parsing the graph client-side.

    @Field
    fun inputSchema(source: Pipeline): JsonElement? = source.inputNode?.schema

    @Field
    fun outputType(source: Pipeline): String? = source.outputNode?.let { node ->
        node.outputType.ifBlank { if (node.schema != null) InputNode.JSON_TYPE else "" }.ifBlank { null }
    }

    @Field
    fun outputSchema(source: Pipeline): JsonElement? = source.outputNode?.schema

    @Field
    fun hasOutput(source: Pipeline): Boolean = source.outputNode != null

    // The typed fields of a declared object *shape* Input/Output (`acceptedType`/`outputType` = "SHAPE"),
    // so a For Each or a consuming pipeline sees the shape's fields — empty when not a shape.

    @Field
    fun inputFields(source: Pipeline): List<ShapeField> = source.inputNode?.fields.orEmpty()

    @Field
    fun outputFields(source: Pipeline): List<ShapeField> = source.outputNode?.fields.orEmpty()
}

/** Field wiring for the `PipelineShapeField` GraphQL type (one field of a declared object shape); source is [ShapeField]. */
@TypeController(type = "PipelineShapeField")
class PipelineShapeFieldController : GraphQLController<ShapeField> {

    @Field
    fun name(source: ShapeField): String = source.name

    @Field
    fun type(source: ShapeField): String = source.type
}

/** Field wiring for the `BrokenPipeline` GraphQL type — a pipeline whose graph no longer decodes. */
@TypeController
class BrokenPipelineController : GraphQLController<BrokenPipeline> {

    @Field
    fun id(source: BrokenPipeline): UUID = source.id

    @Field
    fun name(source: BrokenPipeline): String = source.name

    @Field
    fun key(source: BrokenPipeline): String = source.key

    @Field
    fun error(source: BrokenPipeline): String = source.error
}
