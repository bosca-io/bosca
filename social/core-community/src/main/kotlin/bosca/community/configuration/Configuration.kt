package bosca.community.configuration

import bosca.community.security.CommunityGroupPermissionEvaluator
import bosca.community.security.PrayerPermissionEvaluator
import bosca.community.service.CommunityService
import bosca.community.service.PrayerService
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService

@Providers
class Configuration {

    @Provider(singleton = true)
    fun communityGroupPermissionEvaluator(
        service: CommunityService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator
    ) = CommunityGroupPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun prayerPermissionEvaluator(
        service: PrayerService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator
    ) = PrayerPermissionEvaluator(service, securityService, groupEvaluator)
}
