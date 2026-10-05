package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.project.ProjectAnalyticsApplication
import bosca.workops.model.project.ProjectAnalyticsService
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.project.ProjectRepository
import bosca.workops.repository.ProjectPermissionRepository
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectAnalyticsConfigService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectRepositoryService
import bosca.workops.service.ProjectService

@TypeController(type = "WorkOpsProject")
class ProjectTypeController(
    private val programService: ProgramService,
    private val projectService: ProjectService,
    private val profileService: ProfileService,
    private val profilePermissions: ProfilePermissionEvaluator,
    private val permissionEvaluator: ProjectPermissionEvaluator,
    private val analyticsConfig: ProjectAnalyticsConfigService,
    private val repositoryConfig: ProjectRepositoryService,
) : GraphQLController<Project> {

    @Field fun id(p: Project) = p.id
    @Field fun key(p: Project) = p.key
    @Field fun name(p: Project) = p.name
    @Field fun description(p: Project) = p.description
    @Field fun ownerProfileId(p: Project) = p.ownerProfileId
    @Field fun defaultTaskTypeSchemeId(p: Project) = p.defaultTaskTypeSchemeId
    @Field fun defaultWorkflowSchemeId(p: Project) = p.defaultWorkflowSchemeId
    @Field fun taskCreationFormSchemaKey(p: Project) = p.taskCreationFormSchemaKey
    @Field fun archivedAt(p: Project) = p.archivedAt
    @Field fun createdAt(p: Project) = p.createdAt
    @Field fun modifiedAt(p: Project) = p.modifiedAt
    @Field fun version(p: Project) = p.version

    @Field
    suspend fun owner(authentication: AuthenticationContext, project: Project): Profile? {
        val profile = profileService.getById(project.ownerProfileId)
        return if (profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW)) profile else null
    }

    @Field
    suspend fun program(authentication: AuthenticationContext, project: Project): Program {
        return programService.getById(project.programId)
            ?: error("Program ${project.programId} missing for project ${project.id}")
    }

    @Field
    suspend fun permissions(authentication: AuthenticationContext, project: Project): List<EntityPermission> {
        if (!permissionEvaluator.isAllowed(authentication, project, PermissionAction.MANAGE)) return emptyList()
        return projectService.getPermissions(project)
    }

    @Field
    suspend fun analyticsApplications(project: Project): List<ProjectAnalyticsApplication> =
        analyticsConfig.listApplications(project.id)

    @Field
    suspend fun analyticsServices(project: Project): List<ProjectAnalyticsService> =
        analyticsConfig.listServices(project.id)

    @Field
    suspend fun repositories(project: Project): List<ProjectRepository> =
        repositoryConfig.list(project.id)
}

@TypeController(type = "WorkOpsProjectRepository")
class ProjectRepositoryTypeController : GraphQLController<ProjectRepository> {
    @Field fun id(r: ProjectRepository) = r.id
    @Field fun projectId(r: ProjectRepository) = r.projectId
    @Field fun repositoryId(r: ProjectRepository) = r.repositoryId
}

@TypeController(type = "WorkOpsProjectAnalyticsApplication")
class ProjectAnalyticsApplicationTypeController : GraphQLController<ProjectAnalyticsApplication> {
    @Field fun id(a: ProjectAnalyticsApplication) = a.id
    @Field fun projectId(a: ProjectAnalyticsApplication) = a.projectId
    @Field fun applicationId(a: ProjectAnalyticsApplication) = a.applicationId
}

@TypeController(type = "WorkOpsProjectAnalyticsService")
class ProjectAnalyticsServiceTypeController : GraphQLController<ProjectAnalyticsService> {
    @Field fun id(s: ProjectAnalyticsService) = s.id
    @Field fun projectId(s: ProjectAnalyticsService) = s.projectId
    @Field fun service(s: ProjectAnalyticsService) = s.service
}

object WorkOpsProjects

@TypeController
class ProjectQueryController(
    private val service: ProjectService,
    private val permissionEvaluator: ProjectPermissionEvaluator,
) : GraphQLController<WorkOpsProjects> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<Project> {
        val projects = service.listAll()
        return permissionEvaluator.filterAllowed(authentication, projects, PermissionAction.VIEW)
    }

    @Field
    suspend fun project(authentication: AuthenticationContext, id: UUID): Project? {
        val project = service.getById(id) ?: return null
        return if (permissionEvaluator.isAllowed(authentication, project, PermissionAction.VIEW)) project else null
    }

    @Field
    suspend fun projectByKey(authentication: AuthenticationContext, key: String): Project? {
        val project = service.getByKey(key) ?: return null
        return if (permissionEvaluator.isAllowed(authentication, project, PermissionAction.VIEW)) project else null
    }

    @Field
    suspend fun byProgram(
        authentication: AuthenticationContext,
        programId: UUID,
        offset: Long,
        limit: Int,
    ): List<Project> {
        val projects = service.listByProgram(programId, offset, limit)
        return permissionEvaluator.filterAllowed(authentication, projects, PermissionAction.VIEW)
    }
}

object WorkOpsProjectsMutation

@TypeController
class ProjectMutationController(
    private val service: ProjectService,
    private val programService: ProgramService,
    private val programPermissions: ProgramPermissionEvaluator,
    private val permissionEvaluator: ProjectPermissionEvaluator,
    private val permissionRepo: ProjectPermissionRepository,
    private val analyticsConfig: ProjectAnalyticsConfigService,
    private val repositoryConfig: ProjectRepositoryService,
) : GraphQLController<WorkOpsProjectsMutation> {

    @Field
    suspend fun create(authentication: AuthenticationContext, input: ProjectInput): Project {
        val program = programService.getById(input.programId)
            ?: error("Program ${input.programId} not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        return service.create(input)
    }

    @Field
    suspend fun update(
        authentication: AuthenticationContext,
        id: UUID,
        input: ProjectInput,
        expectedVersion: Long,
    ): Project {
        val project = service.getById(id) ?: error("Project $id not found")
        permissionEvaluator.verifyAllowed(authentication, project, PermissionAction.EDIT)
        return service.update(id, input, expectedVersion)
    }

    @Field
    suspend fun move(
        authentication: AuthenticationContext,
        id: UUID,
        programId: UUID,
        expectedVersion: Long,
    ): Project {
        val project = service.getById(id) ?: error("Project $id not found")
        permissionEvaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        val targetProgram = programService.getById(programId) ?: error("Program $programId not found")
        programPermissions.verifyAllowed(authentication, targetProgram, PermissionAction.MANAGE)
        return service.move(id, programId, expectedVersion)
    }

    @Field
    suspend fun archive(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): Project {
        val project = service.getById(id) ?: error("Project $id not found")
        permissionEvaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        return service.archive(id, expectedVersion)
    }

    @Field
    suspend fun unarchive(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): Project {
        val project = service.getById(id) ?: error("Project $id not found")
        permissionEvaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        return service.unarchive(id, expectedVersion)
    }

    @Field
    suspend fun addPermission(authentication: AuthenticationContext, id: UUID, groupId: UUID, action: PermissionAction): Boolean {
        val project = service.getById(id) ?: error("Project $id not found")
        permissionEvaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        permissionRepo.add(id, groupId, action)
        return true
    }

    @Field
    suspend fun removePermission(authentication: AuthenticationContext, id: UUID, groupId: UUID, action: PermissionAction): Boolean {
        val project = service.getById(id) ?: error("Project $id not found")
        permissionEvaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        permissionRepo.delete(id, groupId, action)
        return true
    }

    @Field
    suspend fun addAnalyticsApplication(
        authentication: AuthenticationContext,
        projectId: UUID,
        applicationId: String,
    ): ProjectAnalyticsApplication {
        verifyManage(authentication, projectId)
        return analyticsConfig.addApplication(projectId, applicationId)
    }

    @Field
    suspend fun removeAnalyticsApplication(authentication: AuthenticationContext, projectId: UUID, id: UUID): Boolean {
        verifyManage(authentication, projectId)
        analyticsConfig.removeApplication(projectId, id)
        return true
    }

    @Field
    suspend fun addAnalyticsService(
        authentication: AuthenticationContext,
        projectId: UUID,
        service: String,
    ): ProjectAnalyticsService {
        verifyManage(authentication, projectId)
        return analyticsConfig.addService(projectId, service)
    }

    @Field
    suspend fun removeAnalyticsService(authentication: AuthenticationContext, projectId: UUID, id: UUID): Boolean {
        verifyManage(authentication, projectId)
        analyticsConfig.removeService(projectId, id)
        return true
    }

    @Field
    suspend fun addProjectRepository(
        authentication: AuthenticationContext,
        projectId: UUID,
        repositoryId: UUID,
    ): ProjectRepository {
        verifyManage(authentication, projectId)
        return repositoryConfig.add(projectId, repositoryId)
    }

    @Field
    suspend fun removeProjectRepository(authentication: AuthenticationContext, projectId: UUID, id: UUID): Boolean {
        verifyManage(authentication, projectId)
        repositoryConfig.remove(projectId, id)
        return true
    }

    private suspend fun verifyManage(authentication: AuthenticationContext, projectId: UUID) {
        val project = service.getById(projectId) ?: error("Project $projectId not found")
        permissionEvaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE)
    }
}
