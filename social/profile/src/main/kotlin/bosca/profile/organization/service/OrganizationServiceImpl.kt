package bosca.profile.organization.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.StringKeySerializer
import bosca.cache.serializers.UUIDKeySerializer
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.graphql.Batch
import bosca.profile.model.ProfileType
import bosca.profile.organization.events.OrganizationCreated
import bosca.profile.organization.events.OrganizationDeleted
import bosca.profile.organization.events.OrganizationDomainAdded
import bosca.profile.organization.events.OrganizationMemberAdded
import bosca.profile.organization.events.OrganizationMemberRemoved
import bosca.profile.organization.events.OrganizationUpdated
import bosca.profile.organization.events.dispatch
import bosca.profile.organization.model.Organization
import bosca.profile.organization.model.OrganizationDomain
import bosca.profile.organization.model.OrganizationDomainInput
import bosca.profile.organization.model.OrganizationInput
import bosca.profile.organization.model.OrganizationMember
import bosca.profile.organization.model.OrganizationPermission
import bosca.profile.organization.model.OrganizationSignupEmail
import bosca.profile.organization.model.OrganizationSignupEmailInput
import bosca.profile.organization.model.OrganizationSignupGroupType
import bosca.profile.organization.model.OrganizationSignupToken
import bosca.profile.organization.model.OrganizationSignupTokenInput
import bosca.profile.organization.repository.OrganizationDomainsRepository
import bosca.profile.organization.repository.OrganizationMembersRepository
import bosca.profile.organization.repository.OrganizationPermissionRepository
import bosca.profile.organization.repository.OrganizationRepository
import bosca.profile.organization.repository.OrganizationSignupEmailRepository
import bosca.profile.organization.repository.OrganizationSignupTokenRepository
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.service.ProfileService
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class OrganizationServiceImpl(
    private val repository: OrganizationRepository,
    private val permissionRepository: OrganizationPermissionRepository,
    private val domainRepository: OrganizationDomainsRepository,
    private val membersRepository: OrganizationMembersRepository,
    private val emailRepository: OrganizationSignupEmailRepository,
    private val tokenRepository: OrganizationSignupTokenRepository,
    private val profileService: ProfileService,
    private val securityService: ObjectProvider<SecurityService>,
) : OrganizationService {

    private val organizationByIdCache = ServiceCache(
        "organization:id",
        UUIDKeySerializer,
        { keys, batch ->
            batch.setData(keys, repository.getByIds(keys).associateBy { it.id })
        }
    ) {
        repository.getById(it)
    }

    private val organizationByDomainCache = ServiceCache(
        "organization:domain",
        StringKeySerializer
    ) {
        domainRepository.getDomain(it)
    }

    private val organizationPermissionsById = ServiceCache<UUID, List<EntityPermission>>(
        "organization:permissions:id",
        UUIDKeySerializer,
        { keys, batch ->
            val permissions = permissionRepository.getBatch(keys).groupBy { it.organizationId }
            batch.keys.forEach { id ->
                batch.setData(id, permissions[id] ?: emptyList())
            }
            batch.ensureNotNull(emptyList())
        }
    ) {
        permissionRepository.getById(it)
    }

    override suspend fun getAll(offset: Long, limit: Int): List<Organization> = repository.getAll(offset, limit)

    override suspend fun getOrganization(id: UUID): Organization {
        return organizationByIdCache.get(id) ?: error("Organization not found: $id")
    }

    override suspend fun getOrganizationByProfile(profileId: UUID): Organization? {
        return repository.getByProfileId(profileId)
    }

    override suspend fun getOrganizations(ids: List<UUID>): List<Organization> {
        return organizationByIdCache.getAll(ids).filterNotNull()
    }

    override suspend fun addOrganizationsToBatch(batch: Batch<UUID, Organization>) {
        organizationByIdCache.addToBatch(batch)
    }

    override suspend fun add(organization: OrganizationInput, profile: ProfileInput, principalId: UUID?): Organization {
        val organization = transaction {
            val profile = profileService.add(profile, ProfileType.ORGANIZATION)
            val newOrganization = repository.add(organization.toOrganization(profile.id))
            var organizationAdminGroup = Group(
                name = "${newOrganization.id}.admins",
                description = "${organization.name} Admins",
                type = GroupType.SYSTEM,
            )
            var organizationUserGroup = Group(
                name = "${newOrganization.id}.users",
                description = "${organization.name} Users",
                type = GroupType.SYSTEM,
            )
            val securityService = securityService.get()
            organizationAdminGroup = securityService.addGroup(organizationAdminGroup)
            organizationUserGroup = securityService.addGroup(organizationUserGroup)
            principalId?.let {
                securityService.addPrincipalGroup(it, organizationAdminGroup.id)
                addMember(newOrganization.id, it)
            }
            organization.domains.forEach { domain ->
                if (domain.defaultGroupId == null && domain.type != null) {
                    when (domain.type ?: error("Invalid organization signup group type")) {
                        OrganizationSignupGroupType.ADMINISTRATORS -> addDomain(newOrganization.id, domain.copy(defaultGroupId = organizationAdminGroup.id))
                        OrganizationSignupGroupType.USERS -> addDomain(newOrganization.id, domain.copy(defaultGroupId = organizationUserGroup.id))
                        OrganizationSignupGroupType.UNKNOWN -> error("Invalid organization signup group type")
                    }
                } else {
                    addDomain(newOrganization.id, domain)
                }
            }
            organization.signupTokens.forEach { token ->
                addSignupToken(newOrganization.id, token)
            }
            organization.signupEmails.forEach { email ->
                when (email.type) {
                    OrganizationSignupGroupType.ADMINISTRATORS -> addSignupEmail(newOrganization.id, email)
                    OrganizationSignupGroupType.USERS -> addSignupEmail(newOrganization.id, email)
                    OrganizationSignupGroupType.UNKNOWN -> error("Invalid organization signup group type")
                }
            }
            addPermission(
                OrganizationPermission(
                    organizationId = newOrganization.id,
                    groupId = organizationAdminGroup.id,
                    action = PermissionAction.MANAGE
                )
            )
            addPermission(
                OrganizationPermission(
                    organizationId = newOrganization.id,
                    groupId = organizationUserGroup.id,
                    action = PermissionAction.VIEW
                )
            )
            newOrganization
        }
        OrganizationCreated(organization).dispatch()
        return organization
    }

    override suspend fun edit(organization: OrganizationInput): Organization {
        val organization = transaction {
            val current = repository.getById(organization.id) ?: error("Organization not found: ${organization.id}")
            val edit = current.copy(
                name = organization.name,
                attributes = organization.attributes,
                modified = OffsetDateTime.now()
            )
            val organization = repository.edit(edit)
            organizationByIdCache.remove(organization.id)
            organizationPermissionsById.remove(organization.id)
            organization
        }
        OrganizationUpdated(organization).dispatch()
        return organization
    }

    override suspend fun edit(organization: OrganizationInput, profile: ProfileInput): Organization {
        val organization = transaction {
            val current = repository.getById(organization.id) ?: error("Organization not found: ${organization.id}")
            // TODO: don't make everything editable
            profileService.edit(current.profileId, profile)
            val organization = repository.edit(organization.toOrganization(current.profileId))
            organizationByIdCache.remove(organization.id)
            organizationPermissionsById.remove(organization.id)
            organization
        }
        OrganizationUpdated(organization).dispatch()
        return organization
    }

    override suspend fun delete(id: UUID) {
        val organization = repository.getById(id)
        profileService.delete(organization?.profileId ?: error("Organization not found: $id"))
        repository.deleteById(id)
        organizationByIdCache.remove(id)
        organizationPermissionsById.remove(id)
        OrganizationDeleted(organization).dispatch()
    }

    override suspend fun getSignupToken(token: String): OrganizationSignupToken? {
        return tokenRepository.getToken(token)
    }

    override suspend fun getSignupTokens(organizationId: UUID): List<OrganizationSignupToken> {
        return tokenRepository.getAll(organizationId)
    }

    override suspend fun addSignupToken(organizationId: UUID, token: OrganizationSignupTokenInput): OrganizationSignupToken {
        val group = securityService.get().getGroupByName(
            "$organizationId.${
                when (token.type) {
                    OrganizationSignupGroupType.ADMINISTRATORS -> "admins"
                    OrganizationSignupGroupType.USERS -> "users"
                    OrganizationSignupGroupType.UNKNOWN -> error("Invalid organization signup group type")
                }
            }", GroupType.SYSTEM
        ) ?: error("invalid organization id")
        return tokenRepository.add(token.toOrganizationSignupToken(organizationId, group.id))
    }

    override suspend fun deleteSignupToken(organizationId: UUID, token: String) {
        tokenRepository.delete(organizationId, token)
    }

    override suspend fun getSignupEmail(email: String): List<OrganizationSignupEmail> {
        return emailRepository.getEmail(email)
    }

    override suspend fun getSignupEmails(organizationId: UUID): List<OrganizationSignupEmail> {
        return emailRepository.getAll(organizationId)
    }

    override suspend fun addSignupEmail(organizationId: UUID, email: OrganizationSignupEmailInput): OrganizationSignupEmail {
        val group = securityService.get().getGroupByName(
            "$organizationId.${
                when (email.type) {
                    OrganizationSignupGroupType.ADMINISTRATORS -> "admins"
                    OrganizationSignupGroupType.USERS -> "users"
                    OrganizationSignupGroupType.UNKNOWN -> error("Invalid organization signup group type")
                }
            }", GroupType.SYSTEM
        ) ?: error("invalid organization id")
        return emailRepository.add(email.toOrganizationSignupEmail(organizationId, group.id))
    }

    override suspend fun deleteSignupEmail(organizationId: UUID, email: String) {
        emailRepository.delete(organizationId, email)
    }

    override suspend fun addPermission(permission: OrganizationPermission) {
        permissionRepository.add(permission)
        organizationPermissionsById.remove(permission.organizationId)
        val organization = getOrganization(permission.organizationId)
        OrganizationUpdated(organization).dispatch()
    }

    override suspend fun removePermission(id: UUID, groupId: UUID, action: PermissionAction) {
        permissionRepository.delete(OrganizationPermission(id, groupId, action))
        organizationPermissionsById.remove(id)
        val organization = getOrganization(id)
        OrganizationUpdated(organization).dispatch()
    }

    override suspend fun getPermissions(id: UUID): List<EntityPermission> {
        return organizationPermissionsById.get(id) ?: emptyList()
    }

    override suspend fun getPermissions(entity: Organization): List<EntityPermission> {
        return getPermissions(entity.id)
    }

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        organizationPermissionsById.addToBatch(batch)
    }

    override suspend fun addDomain(id: UUID, domain: OrganizationDomainInput) {
        var domain = domain
        if (domain.defaultGroupId == null) {
            val group = securityService.get().getGroupByName(
                "$id.${
                    when (domain.type) {
                        OrganizationSignupGroupType.ADMINISTRATORS -> "admins"
                        OrganizationSignupGroupType.USERS -> "users"
                        else -> error("Invalid organization signup group type")
                    }
                }", GroupType.SYSTEM
            ) ?: error("invalid organization id")
            domain = domain.copy(defaultGroupId = group.id)
        }
        domainRepository.add(domain.toDomain(id))
        val organization = getOrganization(id)
        OrganizationDomainAdded(organization).dispatch()
    }

    override suspend fun removeDomain(id: UUID, domain: String) {
        domainRepository.delete(id, domain)
        val organization = getOrganization(id)
        OrganizationDomainAdded(organization).dispatch()
    }

    override suspend fun getDomains(id: UUID): List<OrganizationDomain> {
        return domainRepository.getAll(id)
    }

    override suspend fun addMemberByEmail(email: String, principalId: UUID) {
        val domain = email.split("@").last().trim().lowercase()
        addMemberByDomain(
            domain,
            principalId
        )
        emailRepository.getEmail(email).forEach {
            it.groupId?.let {
                securityService.get().addPrincipalGroup(principalId, it)
            }
            addMember(it.organizationId, principalId)
        }
    }

    override suspend fun addMemberByToken(token: String, principalId: UUID) {
        tokenRepository.getToken(token)?.let {
            it.groupId?.let {
                securityService.get().addPrincipalGroup(principalId, it)
            }
            addMember(it.organizationId, principalId)
        }
    }

    override suspend fun addMemberByDomain(domain: String, principalId: UUID) {
        val organization = organizationByDomainCache.get(domain.lowercase())
        organization?.takeIf { it.autoJoin }?.let {
            it.groupId?.let {
                securityService.get().addPrincipalGroup(principalId, it)
            }
            addMember(it.organizationId, principalId)
        }
    }

    override suspend fun addMember(id: UUID, principalId: UUID) {
        membersRepository.add(OrganizationMember(id, principalId))
        val organization = getOrganization(id)
        OrganizationMemberAdded(organization, principalId).dispatch()
    }

    override suspend fun removeMember(id: UUID, principalId: UUID) {
        membersRepository.delete(OrganizationMember(id, principalId))
        val organization = getOrganization(id)
        OrganizationMemberRemoved(organization, principalId).dispatch()
    }

    override suspend fun getMembers(id: UUID, offset: Long, limit: Int): List<OrganizationMember> {
        return membersRepository.getAll(id, offset, limit)
    }

    override suspend fun getMemberCount(id: UUID): Long {
        return membersRepository.getCount(id)
    }

    override suspend fun getMemberOrganizations(principalId: UUID): List<OrganizationMember> {
        return membersRepository.getOrganizationsByPrincipals(listOf(principalId))
    }

    override suspend fun getMemberOrganizations(principalIds: List<UUID>): List<OrganizationMember> {
        return membersRepository.getOrganizationsByPrincipals(principalIds)
    }
}