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
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.Program
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.Project
import bosca.workops.repository.ProgramPermissionRepository
import bosca.workops.service.PortfolioPermissionEvaluator
import bosca.workops.service.PortfolioService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectService

@TypeController(type = "WorkOpsProgram")
class ProgramTypeController(
    private val portfolioService: PortfolioService,
    private val projectService: ProjectService,
    private val programService: ProgramService,
    private val profileService: ProfileService,
    private val profilePermissions: ProfilePermissionEvaluator,
    private val permissionEvaluator: ProgramPermissionEvaluator,
) : GraphQLController<Program> {

    @Field fun id(p: Program) = p.id
    @Field fun key(p: Program) = p.key
    @Field fun name(p: Program) = p.name
    @Field fun description(p: Program) = p.description
    @Field fun ownerProfileId(p: Program) = p.ownerProfileId
    @Field fun startDate(p: Program) = p.startDate
    @Field fun targetDate(p: Program) = p.targetDate
    @Field fun archivedAt(p: Program) = p.archivedAt
    @Field fun createdAt(p: Program) = p.createdAt
    @Field fun modifiedAt(p: Program) = p.modifiedAt
    @Field fun version(p: Program) = p.version

    @Field
    suspend fun owner(authentication: AuthenticationContext, program: Program): Profile? {
        val profile = profileService.getById(program.ownerProfileId)
        return if (profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW)) profile else null
    }

    @Field
    suspend fun portfolio(authentication: AuthenticationContext, program: Program): Portfolio {
        return portfolioService.getById(program.portfolioId)
            ?: error("Portfolio ${program.portfolioId} missing for program ${program.id}")
    }

    @Field
    suspend fun projects(program: Program, offset: Long = 0, limit: Int = 50): List<Project> =
        projectService.listByProgram(program.id, offset, limit)

    @Field
    suspend fun permissions(authentication: AuthenticationContext, program: Program): List<EntityPermission> {
        if (!permissionEvaluator.isAllowed(authentication, program, PermissionAction.MANAGE)) return emptyList()
        return programService.getPermissions(program)
    }
}

object WorkOpsPrograms

@TypeController
class ProgramQueryController(
    private val service: ProgramService,
    private val permissionEvaluator: ProgramPermissionEvaluator,
) : GraphQLController<WorkOpsPrograms> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<Program> {
        val programs = service.listAll()
        return permissionEvaluator.filterAllowed(authentication, programs, PermissionAction.VIEW)
    }

    @Field
    suspend fun program(authentication: AuthenticationContext, id: UUID): Program? {
        val program = service.getById(id) ?: return null
        return if (permissionEvaluator.isAllowed(authentication, program, PermissionAction.VIEW)) program else null
    }

    @Field
    suspend fun byPortfolio(
        authentication: AuthenticationContext,
        portfolioId: UUID,
        offset: Long,
        limit: Int,
    ): List<Program> {
        val programs = service.listByPortfolio(portfolioId, offset, limit)
        return permissionEvaluator.filterAllowed(authentication, programs, PermissionAction.VIEW)
    }
}

object WorkOpsProgramsMutation

@TypeController
class ProgramMutationController(
    private val service: ProgramService,
    private val portfolioService: PortfolioService,
    private val portfolioPermissions: PortfolioPermissionEvaluator,
    private val permissionEvaluator: ProgramPermissionEvaluator,
    private val permissionRepo: ProgramPermissionRepository,
) : GraphQLController<WorkOpsProgramsMutation> {

    @Field
    suspend fun create(authentication: AuthenticationContext, input: ProgramInput): Program {
        val portfolio = portfolioService.getById(input.portfolioId)
            ?: error("Portfolio ${input.portfolioId} not found")
        portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE)
        return service.create(input)
    }

    @Field
    suspend fun update(
        authentication: AuthenticationContext,
        id: UUID,
        input: ProgramInput,
        expectedVersion: Long,
    ): Program {
        val program = service.getById(id) ?: error("Program $id not found")
        permissionEvaluator.verifyAllowed(authentication, program, PermissionAction.EDIT)
        return service.update(id, input, expectedVersion)
    }

    @Field
    suspend fun archive(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): Program {
        val program = service.getById(id) ?: error("Program $id not found")
        permissionEvaluator.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        return service.archive(id, expectedVersion)
    }

    @Field
    suspend fun unarchive(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): Program {
        val program = service.getById(id) ?: error("Program $id not found")
        permissionEvaluator.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        return service.unarchive(id, expectedVersion)
    }

    @Field
    suspend fun addPermission(authentication: AuthenticationContext, id: UUID, groupId: UUID, action: PermissionAction): Boolean {
        val program = service.getById(id) ?: error("Program $id not found")
        permissionEvaluator.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        permissionRepo.add(id, groupId, action)
        return true
    }

    @Field
    suspend fun removePermission(authentication: AuthenticationContext, id: UUID, groupId: UUID, action: PermissionAction): Boolean {
        val program = service.getById(id) ?: error("Program $id not found")
        permissionEvaluator.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        permissionRepo.delete(id, groupId, action)
        return true
    }
}
