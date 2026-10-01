package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.component.Component
import bosca.workops.model.component.CreateComponentInput
import bosca.workops.model.label.CreateLabelInput
import bosca.workops.model.label.Label
import bosca.workops.model.milestone.CreateMilestoneInput
import bosca.workops.model.milestone.Milestone
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.version.CreateVersionInput
import bosca.workops.model.version.Version
import bosca.workops.service.ComponentService
import bosca.workops.service.LabelService
import bosca.workops.service.MilestoneService
import bosca.workops.service.PortfolioPermissionEvaluator
import bosca.workops.service.PortfolioService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.VersionService

@TypeController(type = "WorkOpsVersion")
class VersionTypeController(
    private val projectService: ProjectService,
) : GraphQLController<Version> {

    @Field fun id(v: Version) = v.id
    @Field fun projectId(v: Version) = v.projectId
    @Field fun name(v: Version) = v.name
    @Field fun description(v: Version) = v.description
    @Field fun startDate(v: Version) = v.startDate
    @Field fun releaseDate(v: Version) = v.releaseDate
    @Field fun released(v: Version) = v.released
    @Field fun archived(v: Version) = v.archived
    @Field fun sequenceNumber(v: Version) = v.sequenceNumber
    @Field fun version(v: Version) = v.version

    @Field
    suspend fun project(version: Version): Project =
        projectService.getById(version.projectId)
            ?: error("Project ${version.projectId} missing for version ${version.id}")
}

object WorkOpsVersions

@TypeController
class VersionQueryController(
    private val service: VersionService,
    private val projectService: ProjectService,
    private val projectPermissions: ProjectPermissionEvaluator,
) : GraphQLController<WorkOpsVersions> {

    @Field
    suspend fun version(authentication: AuthenticationContext, id: UUID): Version? {
        val version = service.getById(id) ?: return null
        val project = projectService.getById(version.projectId) ?: return null
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
        return version
    }

    @Field
    suspend fun byProject(authentication: AuthenticationContext, projectId: UUID): List<Version> {
        val project = projectService.getById(projectId) ?: return emptyList()
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
        return service.listByProject(projectId)
    }
}

object WorkOpsVersionsMutation

@TypeController
class VersionMutationController(
    private val service: VersionService,
    private val projectService: ProjectService,
    private val projectPermissions: ProjectPermissionEvaluator,
) : GraphQLController<WorkOpsVersionsMutation> {

    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreateVersionInput): Version {
        val project = projectService.getById(input.projectId) ?: error("Project ${input.projectId} not found")
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        return service.create(input)
    }

    @Field
    suspend fun release(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): Version {
        val version = service.getById(id) ?: error("Version $id not found")
        val project = projectService.getById(version.projectId) ?: error("Project not found")
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        return service.release(id, expectedVersion)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        val version = service.getById(id) ?: error("Version $id not found")
        val project = projectService.getById(version.projectId) ?: error("Project not found")
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        service.delete(id)
        return true
    }
}

@TypeController(type = "WorkOpsComponent")
class ComponentTypeController(
    private val projectService: ProjectService,
) : GraphQLController<Component> {

    @Field fun id(c: Component) = c.id
    @Field fun projectId(c: Component) = c.projectId
    @Field fun name(c: Component) = c.name
    @Field fun description(c: Component) = c.description
    @Field fun defaultAssigneeProfileId(c: Component) = c.defaultAssigneeProfileId
    @Field fun leadProfileId(c: Component) = c.leadProfileId
    @Field fun assigneeMode(c: Component) = c.assigneeMode
    @Field fun version(c: Component) = c.version

    @Field
    suspend fun project(component: Component): Project =
        projectService.getById(component.projectId)
            ?: error("Project ${component.projectId} missing for component ${component.id}")
}

object WorkOpsComponents

@TypeController
class ComponentQueryController(
    private val service: ComponentService,
    private val projectService: ProjectService,
    private val projectPermissions: ProjectPermissionEvaluator,
) : GraphQLController<WorkOpsComponents> {

    @Field
    suspend fun component(authentication: AuthenticationContext, id: UUID): Component? {
        val component = service.getById(id) ?: return null
        val project = projectService.getById(component.projectId) ?: return null
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
        return component
    }

    @Field
    suspend fun byProject(authentication: AuthenticationContext, projectId: UUID): List<Component> {
        val project = projectService.getById(projectId) ?: return emptyList()
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
        return service.listByProject(projectId)
    }
}

object WorkOpsComponentsMutation

@TypeController
class ComponentMutationController(
    private val service: ComponentService,
    private val projectService: ProjectService,
    private val projectPermissions: ProjectPermissionEvaluator,
) : GraphQLController<WorkOpsComponentsMutation> {

    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreateComponentInput): Component {
        val project = projectService.getById(input.projectId) ?: error("Project ${input.projectId} not found")
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        return service.create(input)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        val component = service.getById(id) ?: error("Component $id not found")
        val project = projectService.getById(component.projectId) ?: error("Project not found")
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        service.delete(id)
        return true
    }
}

@TypeController(type = "WorkOpsLabel")
class LabelTypeController : GraphQLController<Label> {
    @Field fun id(l: Label) = l.id
    @Field fun name(l: Label) = l.name
    @Field fun colorHex(l: Label) = l.colorHex
    @Field fun scope(l: Label) = l.scope
    @Field fun portfolioId(l: Label) = l.portfolioId
    @Field fun programId(l: Label) = l.programId
    @Field fun projectId(l: Label) = l.projectId
    @Field fun version(l: Label) = l.version
}

object WorkOpsLabels

@TypeController
class LabelQueryController(
    private val service: LabelService,
    private val projectService: ProjectService,
    private val projectPermissions: ProjectPermissionEvaluator,
    private val programService: ProgramService,
    private val programPermissions: ProgramPermissionEvaluator,
    private val portfolioService: PortfolioService,
    private val portfolioPermissions: PortfolioPermissionEvaluator,
) : GraphQLController<WorkOpsLabels> {

    private fun requireAuthenticated(authentication: AuthenticationContext) {
        authentication.principal() ?: error("authentication required")
    }

    @Field
    suspend fun label(authentication: AuthenticationContext, id: UUID): Label? {
        val label = service.getById(id) ?: return null
        label.projectId?.let { pid ->
            val project = projectService.getById(pid) ?: return null
            if (!projectPermissions.isAllowed(authentication, project, PermissionAction.VIEW)) return null
            return label
        }
        label.programId?.let { pid ->
            val program = programService.getById(pid) ?: return null
            if (!programPermissions.isAllowed(authentication, program, PermissionAction.VIEW)) return null
            return label
        }
        label.portfolioId?.let { pid ->
            val portfolio = portfolioService.getById(pid) ?: return null
            if (!portfolioPermissions.isAllowed(authentication, portfolio, PermissionAction.VIEW)) return null
            return label
        }
        requireAuthenticated(authentication)
        return label
    }

    @Field
    suspend fun global(authentication: AuthenticationContext): List<Label> {
        requireAuthenticated(authentication)
        return service.listGlobal()
    }

    @Field
    suspend fun byPortfolio(authentication: AuthenticationContext, portfolioId: UUID): List<Label> {
        val portfolio = portfolioService.getById(portfolioId) ?: return emptyList()
        portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.VIEW)
        return service.listByPortfolio(portfolioId)
    }

    @Field
    suspend fun byProgram(authentication: AuthenticationContext, programId: UUID): List<Label> {
        val program = programService.getById(programId) ?: return emptyList()
        programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        return service.listByProgram(programId)
    }

    @Field
    suspend fun byProject(authentication: AuthenticationContext, projectId: UUID): List<Label> {
        val project = projectService.getById(projectId) ?: return emptyList()
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
        return service.listByProject(projectId)
    }
}

object WorkOpsLabelsMutation

@TypeController
class LabelMutationController(
    private val service: LabelService,
    private val projectService: ProjectService,
    private val projectPermissions: ProjectPermissionEvaluator,
    private val programService: ProgramService,
    private val programPermissions: ProgramPermissionEvaluator,
    private val portfolioService: PortfolioService,
    private val portfolioPermissions: PortfolioPermissionEvaluator,
    private val groupEvaluator: bosca.security.service.GroupEvaluator,
) : GraphQLController<WorkOpsLabelsMutation> {

    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreateLabelInput): Label {
        val projectId = input.projectId
        val programId = input.programId
        val portfolioId = input.portfolioId
        when {
            projectId != null -> {
                val project = projectService.getById(projectId) ?: error("Project not found")
                projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
            }
            programId != null -> {
                val program = programService.getById(programId) ?: error("Program not found")
                programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
            }
            portfolioId != null -> {
                val portfolio = portfolioService.getById(portfolioId) ?: error("Portfolio not found")
                portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE)
            }
            else -> groupEvaluator.verifyHasAdminGroup(authentication)
        }
        return service.create(input)
    }

    @Field
    suspend fun update(
        authentication: AuthenticationContext,
        id: UUID,
        name: String,
        colorHex: String?,
        expectedVersion: Long,
    ): Label {
        val label = service.getById(id) ?: error("Label $id not found")
        verifyLabelManagePermission(authentication, label)
        return service.update(id, name, colorHex, expectedVersion)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        val label = service.getById(id) ?: error("Label $id not found")
        verifyLabelManagePermission(authentication, label)
        service.delete(id)
        return true
    }

    private suspend fun verifyLabelManagePermission(authentication: AuthenticationContext, label: Label) {
        val projectId = label.projectId
        val programId = label.programId
        val portfolioId = label.portfolioId
        when {
            projectId != null -> {
                val project = projectService.getById(projectId) ?: error("Project not found")
                projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
            }
            programId != null -> {
                val program = programService.getById(programId) ?: error("Program not found")
                programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
            }
            portfolioId != null -> {
                val portfolio = portfolioService.getById(portfolioId) ?: error("Portfolio not found")
                portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE)
            }
            else -> groupEvaluator.verifyHasAdminGroup(authentication)
        }
    }
}

@TypeController(type = "WorkOpsMilestone")
class MilestoneTypeController(
    private val programService: ProgramService,
) : GraphQLController<Milestone> {

    @Field fun id(m: Milestone) = m.id
    @Field fun programId(m: Milestone) = m.programId
    @Field fun name(m: Milestone) = m.name
    @Field fun description(m: Milestone) = m.description
    @Field fun targetDate(m: Milestone) = m.targetDate
    @Field fun state(m: Milestone) = m.state
    @Field fun closedAt(m: Milestone) = m.closedAt
    @Field fun version(m: Milestone) = m.version

    @Field
    suspend fun program(milestone: Milestone): Program =
        programService.getById(milestone.programId)
            ?: error("Program ${milestone.programId} missing for milestone ${milestone.id}")
}

object WorkOpsMilestones

@TypeController
class MilestoneQueryController(
    private val service: MilestoneService,
    private val programService: ProgramService,
    private val programPermissions: ProgramPermissionEvaluator,
) : GraphQLController<WorkOpsMilestones> {

    @Field
    suspend fun milestone(authentication: AuthenticationContext, id: UUID): Milestone? {
        val milestone = service.getById(id) ?: return null
        val program = programService.getById(milestone.programId) ?: return null
        programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        return milestone
    }

    @Field
    suspend fun byProgram(authentication: AuthenticationContext, programId: UUID): List<Milestone> {
        val program = programService.getById(programId) ?: return emptyList()
        programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        return service.listByProgram(programId)
    }
}

object WorkOpsMilestonesMutation

@TypeController
class MilestoneMutationController(
    private val service: MilestoneService,
    private val programService: ProgramService,
    private val programPermissions: ProgramPermissionEvaluator,
) : GraphQLController<WorkOpsMilestonesMutation> {

    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreateMilestoneInput): Milestone {
        val program = programService.getById(input.programId) ?: error("Program ${input.programId} not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        return service.create(input)
    }

    @Field
    suspend fun close(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): Milestone {
        val milestone = service.getById(id) ?: error("Milestone $id not found")
        val program = programService.getById(milestone.programId) ?: error("Program not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        return service.close(id, expectedVersion)
    }
}
