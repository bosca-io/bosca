@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.ExportRequest
import bosca.localization.model.ExportResult
import bosca.localization.model.LocalizationProject
import bosca.localization.model.LocalizationString
import bosca.localization.security.LocalizationProjectPermissionEvaluator
import bosca.localization.service.LocalizationService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

/**
 * Resolves fields on the top-level `Localization` GraphQL type (the entry point for
 * `query { localization { ... } }`).
 *
 * Project-scoped authorization runs at the edge: `projects` filters the returned list
 * to projects the caller has VIEW on, and `project(id)` verifies VIEW before returning
 * a specific project. All downstream field resolvers assume the project has already
 * been authorized at this entry point.
 */
@TypeController
class LocalizationQueryController(
    private val service: LocalizationService,
    private val evaluator: LocalizationProjectPermissionEvaluator
) : GraphQLController<Localization> {

    /**
     * Returns localization projects the caller has VIEW permission on.
     *
     * Pagination is applied in-memory after permission filtering because
     * [LocalizationProjectPermissionEvaluator.filterAllowed] must inspect
     * the full project list to determine visibility. This is acceptable
     * because the total number of localization projects is expected to
     * remain small (tens, not thousands).
     */
    @Field
    suspend fun projects(authentication: AuthenticationContext, offset: Int?, limit: Int?): List<LocalizationProject> {
        val allProjects = service.getProjects()
        val allowed = evaluator.filterAllowed(authentication, allProjects, PermissionAction.VIEW)
        if (offset == null || limit == null) return allowed
        require(offset >= 0) { "offset must be non-negative" }
        require(limit > 0) { "limit must be positive" }
        return allowed.drop(offset).take(limit)
    }

    @Field
    suspend fun project(authentication: AuthenticationContext, id: UUID): LocalizationProject? {
        val project = service.getProject(id) ?: return null
        evaluator.verifyAllowed(authentication, project, PermissionAction.VIEW)
        return project
    }

    @Field
    suspend fun string(authentication: AuthenticationContext, id: UUID): LocalizationString? {
        val string = service.getString(id) ?: return null
        val project = service.getProject(string.projectId) ?: return null
        evaluator.verifyAllowed(authentication, project, PermissionAction.VIEW)
        return string
    }

    @Field
    suspend fun stringByKey(authentication: AuthenticationContext, projectId: UUID, key: String): LocalizationString? {
        val project = service.getProject(projectId) ?: return null
        evaluator.verifyAllowed(authentication, project, PermissionAction.VIEW)
        return service.getStringByKey(projectId, key)
    }

    @Field
    suspend fun export(authentication: AuthenticationContext, request: ExportRequest): ExportResult {
        val project = service.getProject(request.projectId) ?: throw NoSuchElementException("Localization project not found: ${request.projectId}")
        evaluator.verifyAllowed(authentication, project, PermissionAction.VIEW)
        return service.export(request)
    }

    @Field
    suspend fun exportDocumentAsHtml(authentication: AuthenticationContext, documentId: UUID, languageTag: String): String {
        val document = service.getProjectDocument(documentId) ?: throw NoSuchElementException("Project document not found: $documentId")
        val project = service.getProject(document.projectId) ?: throw NoSuchElementException("Localization project not found: ${document.projectId}")
        evaluator.verifyAllowed(authentication, project, PermissionAction.VIEW)
        return service.exportDocumentAsHtml(documentId, languageTag)
    }
}
