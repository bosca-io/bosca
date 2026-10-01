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
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.Program
import bosca.workops.repository.PortfolioPermissionRepository
import bosca.workops.service.PortfolioPermissionEvaluator
import bosca.workops.service.PortfolioService
import bosca.workops.service.ProgramService

@TypeController(type = "WorkOpsPortfolio")
class PortfolioTypeController(
    private val portfolioService: PortfolioService,
    private val programService: ProgramService,
    private val profileService: ProfileService,
    private val profilePermissions: ProfilePermissionEvaluator,
    private val permissionEvaluator: PortfolioPermissionEvaluator,
) : GraphQLController<Portfolio> {
    @Field fun id(portfolio: Portfolio) = portfolio.id
    @Field fun key(portfolio: Portfolio) = portfolio.key
    @Field fun name(portfolio: Portfolio) = portfolio.name
    @Field fun description(portfolio: Portfolio) = portfolio.description
    @Field fun ownerProfileId(portfolio: Portfolio) = portfolio.ownerProfileId
    @Field fun archivedAt(portfolio: Portfolio) = portfolio.archivedAt
    @Field fun createdAt(portfolio: Portfolio) = portfolio.createdAt
    @Field fun modifiedAt(portfolio: Portfolio) = portfolio.modifiedAt
    @Field fun version(portfolio: Portfolio) = portfolio.version

    @Field
    suspend fun owner(authentication: AuthenticationContext, portfolio: Portfolio): Profile? {
        val profile = profileService.getById(portfolio.ownerProfileId)
        return if (profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW)) profile else null
    }

    @Field
    suspend fun programs(portfolio: Portfolio, offset: Long = 0, limit: Int = 50): List<Program> =
        programService.listByPortfolio(portfolio.id, offset, limit)

    @Field
    suspend fun permissions(authentication: AuthenticationContext, portfolio: Portfolio): List<EntityPermission> {
        if (!permissionEvaluator.isAllowed(authentication, portfolio, PermissionAction.MANAGE)) return emptyList()
        return portfolioService.getPermissions(portfolio)
    }
}

object WorkOpsPortfolios

@TypeController
class PortfolioQueryController(
    private val service: PortfolioService,
    private val permissionEvaluator: PortfolioPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<WorkOpsPortfolios> {

    @Field
    suspend fun all(authentication: AuthenticationContext, offset: Long, limit: Int): List<Portfolio> {
        val portfolios = service.list(offset, limit)
        return permissionEvaluator.filterAllowed(authentication, portfolios, PermissionAction.VIEW)
    }

    @Field
    suspend fun portfolio(authentication: AuthenticationContext, id: UUID): Portfolio? {
        val portfolio = service.getById(id) ?: return null
        return if (permissionEvaluator.isAllowed(authentication, portfolio, PermissionAction.VIEW)) portfolio else null
    }

    @Field
    suspend fun portfolioByKey(authentication: AuthenticationContext, key: String): Portfolio? {
        val portfolio = service.getByKey(key) ?: return null
        return if (permissionEvaluator.isAllowed(authentication, portfolio, PermissionAction.VIEW)) portfolio else null
    }
}

object WorkOpsPortfoliosMutation

@TypeController
class PortfolioMutationController(
    private val service: PortfolioService,
    private val groupEvaluator: GroupEvaluator,
    private val permissionEvaluator: PortfolioPermissionEvaluator,
    private val permissionRepo: PortfolioPermissionRepository,
) : GraphQLController<WorkOpsPortfoliosMutation> {

    @Field
    suspend fun create(authentication: AuthenticationContext, input: PortfolioInput): Portfolio {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.create(input)
    }

    @Field
    suspend fun update(
        authentication: AuthenticationContext,
        id: UUID,
        input: PortfolioInput,
        expectedVersion: Long,
    ): Portfolio {
        val portfolio = service.getById(id) ?: error("Portfolio $id not found")
        permissionEvaluator.verifyAllowed(authentication, portfolio, PermissionAction.EDIT)
        return service.update(id, input, expectedVersion)
    }

    @Field
    suspend fun archive(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): Portfolio {
        val portfolio = service.getById(id) ?: error("Portfolio $id not found")
        permissionEvaluator.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE)
        return service.archive(id, expectedVersion)
    }

    @Field
    suspend fun unarchive(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): Portfolio {
        val portfolio = service.getById(id) ?: error("Portfolio $id not found")
        permissionEvaluator.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE)
        return service.unarchive(id, expectedVersion)
    }

    @Field
    suspend fun addPermission(
        authentication: AuthenticationContext,
        id: UUID,
        groupId: UUID,
        action: PermissionAction,
    ): Boolean {
        val portfolio = service.getById(id) ?: error("Portfolio $id not found")
        permissionEvaluator.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE)
        permissionRepo.add(id, groupId, action)
        return true
    }

    @Field
    suspend fun removePermission(
        authentication: AuthenticationContext,
        id: UUID,
        groupId: UUID,
        action: PermissionAction,
    ): Boolean {
        val portfolio = service.getById(id) ?: error("Portfolio $id not found")
        permissionEvaluator.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE)
        permissionRepo.delete(id, groupId, action)
        return true
    }
}
