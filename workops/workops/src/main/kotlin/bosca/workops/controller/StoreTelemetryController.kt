package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.release.ReleaseStoreCrashFeedback
import bosca.workops.model.release.ReleaseStoreObservation
import bosca.workops.model.release.ReleaseStoreState
import bosca.workops.model.release.ReleaseStoreTelemetry
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ReleaseService
import bosca.workops.service.StoreTelemetryService

/** Program-authorized query entry for live and historical store telemetry. */
@TypeController(type = "WorkOpsMultiRepoQuery")
class StoreTelemetryQueryController(
    private val releases: ReleaseService,
    private val programs: ProgramService,
    private val permissions: ProgramPermissionEvaluator,
    private val telemetry: StoreTelemetryService,
) : GraphQLController<WorkOpsMultiRepoQuery> {
    @Field
    suspend fun releaseStoreTelemetry(
        authentication: AuthenticationContext,
        releaseId: UUID,
    ): ReleaseStoreTelemetry {
        val release = releases.getById(releaseId) ?: error("Release $releaseId not found")
        val program = programs.getById(release.programId) ?: error("Program ${release.programId} not found")
        permissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        return telemetry.forRelease(releaseId)
    }
}

@TypeController(type = "WorkOpsReleaseStoreTelemetry")
class ReleaseStoreTelemetryTypeController : GraphQLController<ReleaseStoreTelemetry> {
    @Field fun states(source: ReleaseStoreTelemetry) = source.states
    @Field fun observations(source: ReleaseStoreTelemetry) = source.observations
}

@TypeController(type = "WorkOpsReleaseStoreState")
class ReleaseStoreStateTypeController : GraphQLController<ReleaseStoreState> {
    @Field fun projectId(source: ReleaseStoreState) = source.projectId
    @Field fun versionId(source: ReleaseStoreState) = source.versionId
    @Field fun environmentId(source: ReleaseStoreState) = source.environmentId
    @Field fun environmentKey(source: ReleaseStoreState) = source.environmentKey
    @Field fun store(source: ReleaseStoreState) = source.store
    @Field fun applicationId(source: ReleaseStoreState) = source.applicationId
    @Field fun appVersion(source: ReleaseStoreState) = source.appVersion
    @Field fun buildNumber(source: ReleaseStoreState) = source.buildNumber
    @Field fun track(source: ReleaseStoreState) = source.track
    @Field fun rolloutPercentage(source: ReleaseStoreState) = source.rolloutPercentage
    @Field fun releaseState(source: ReleaseStoreState) = source.releaseState
    @Field fun reviewState(source: ReleaseStoreState) = source.reviewState
    @Field fun betaReviewState(source: ReleaseStoreState) = source.betaReviewState
    @Field fun buildProcessingState(source: ReleaseStoreState) = source.buildProcessingState
    @Field fun phasedReleaseState(source: ReleaseStoreState) = source.phasedReleaseState
    @Field fun testFlightGroups(source: ReleaseStoreState) = source.testFlightGroups
    @Field fun testFlightCrashFeedback(source: ReleaseStoreState) = source.testFlightCrashFeedback
}

@TypeController(type = "WorkOpsReleaseStoreCrashFeedback")
class ReleaseStoreCrashFeedbackTypeController : GraphQLController<ReleaseStoreCrashFeedback> {
    @Field fun id(source: ReleaseStoreCrashFeedback) = source.id
    @Field fun comment(source: ReleaseStoreCrashFeedback) = source.comment
    @Field fun email(source: ReleaseStoreCrashFeedback) = source.email
    @Field fun deviceModel(source: ReleaseStoreCrashFeedback) = source.deviceModel
    @Field fun osVersion(source: ReleaseStoreCrashFeedback) = source.osVersion
    @Field fun createdAt(source: ReleaseStoreCrashFeedback) = source.createdAt
}

@TypeController(type = "WorkOpsReleaseStoreObservation")
class ReleaseStoreObservationTypeController : GraphQLController<ReleaseStoreObservation> {
    @Field fun store(source: ReleaseStoreObservation) = source.store
    @Field fun applicationId(source: ReleaseStoreObservation) = source.applicationId
    @Field fun appVersion(source: ReleaseStoreObservation) = source.appVersion
    @Field fun telemetryType(source: ReleaseStoreObservation) = source.telemetryType
    @Field fun observedAt(source: ReleaseStoreObservation) = source.observedAt
    @Field fun payload(source: ReleaseStoreObservation) = source.payload
}
