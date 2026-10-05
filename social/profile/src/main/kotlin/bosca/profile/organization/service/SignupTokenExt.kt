package bosca.profile.organization.service

import bosca.community.service.CommunityService
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.profile.model.Profile
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.service.ProfileService
import bosca.security.model.Principal
import bosca.security.model.SignupToken
import bosca.security.model.SignupTokenType
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("SignupTokenExt")

suspend fun List<SignupToken>.process(
    profile: ProfileInput,
    principal: Principal,
    organizationService: OrganizationService,
    communityService: ObjectProvider<CommunityService>
) {
    forEach { token ->
        when (token.type) {
            SignupTokenType.ORGANIZATION -> {
                try {
                    transaction {
                        organizationService.addMemberByToken(token.token, principal.id)
                    }
                } catch (e: Exception) {
                    log.warn("Error adding member by token", e)
                }
            }

            SignupTokenType.COMMUNITY_GROUP -> {
                try {
                    transaction {
                        communityService.get().addMemberByToken(token.token, principal.id)
                    }
                } catch (e: Exception) {
                    log.warn("Error adding member by token", e)
                }
            }
        }
    }
    profile.attributes.find { it.typeId == "bosca.profiles.email" }
        ?.attributes
        ?.jsonObject
        ?.get("email")
        ?.jsonPrimitive
        ?.content
        ?.let {
            try {
                transaction {
                    organizationService.addMemberByEmail(it, principal.id)
                }
            } catch (e: Exception) {
                log.warn("Error adding member by email", e)
            }
        }
}

suspend fun List<SignupToken>.process(
    profile: Profile,
    principal: Principal,
    profileService: ProfileService,
    organizationService: OrganizationService,
    communityService: ObjectProvider<CommunityService>
) {
    forEach { token ->
        when (token.type) {
            SignupTokenType.ORGANIZATION -> {
                try {
                    transaction {
                        organizationService.addMemberByToken(token.token, principal.id)
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("duplicate key value violates unique constraint") == true) {
                        return@forEach
                    }
                    log.warn("Error adding member by token", e)
                }
            }

            SignupTokenType.COMMUNITY_GROUP -> {
                if (communityService.exists) {
                    try {
                        transaction {
                            communityService.get().addMemberByToken(token.token, principal.id)
                        }
                    } catch (e: Exception) {
                        if (e.message?.contains("duplicate key value violates unique constraint") == true) {
                            return@forEach
                        }
                        log.warn("Error adding member by token", e)
                    }
                }
            }
        }
    }
    val attributes = profileService.getAttributes(profile.id)
    attributes.find { it.typeId == "bosca.profiles.email" }
        ?.attributes
        ?.jsonObject
        ?.get("email")
        ?.jsonPrimitive
        ?.content
        ?.let {
            try {
                transaction {
                    organizationService.addMemberByEmail(it, principal.id)
                }
            } catch (e: Exception) {
                if (e.message?.contains("duplicate key value violates unique constraint") == true) {
                    return@let
                }
                log.warn("Error adding member by email", e)
            }
            if (communityService.exists) {
                try {
                    transaction {
                        communityService.get().addMemberByEmail(it, principal.id)
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("duplicate key value violates unique constraint") == true) {
                        return@let
                    }
                    log.warn("Error adding member by email", e)
                }
            }
        }
}