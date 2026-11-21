package bosca.workops.ui

import bosca.bml.render.InvalidBmlClientStateException
import bosca.bml.render.decodeClientState
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WorkOpsClientIdentityTest {
    private inline fun <reified T> rejects(source: String = "{}") {
        assertFailsWith<InvalidBmlClientStateException> { decodeClientState<T>(source) }
    }

    @Test fun `document and specification actions require their original document and parent identities`() {
        rejects<WorkOpsDocumentModel>()
        rejects<WorkOpsSpecGitModel>()
        rejects<WorkOpsRequirementsModel>()
        rejects<WorkOpsRequirementsModel>("""{"parentId":"task"}""")
        rejects<WorkOpsSpecContextsModel>()
        rejects<WorkOpsSpecContextsModel>("""{"specId":"spec"}""")
        rejects<WorkOpsSpecGenerationsModel>()
        rejects<WorkOpsSpecGenerationsModel>("""{"specId":"spec"}""")
    }

    @Test fun `planning project and board actions cannot deserialize without their owning identity`() {
        rejects<WorkOpsPlanningModel>()
        rejects<WorkOpsProjectDetailModel>()
        rejects<WorkOpsProjectCatalogModel>()
        rejects<WorkOpsProjectCatalogModel>("""{"projectId":"project"}""")
        rejects<WorkOpsProjectDependenciesModel>()
        rejects<WorkOpsProjectRepositoriesModel>()
        rejects<WorkOpsProjectTelemetryModel>()
        rejects<WorkOpsProjectTelemetryModel>("""{"projectId":"project"}""")
        rejects<WorkOpsBoardViewModel>()
        rejects<WorkOpsBoardConfigurationModel>()
        rejects<WorkOpsSprintsModel>()
    }

    @Test fun `delivery actions require release program and approval run identities`() {
        rejects<WorkOpsReleaseDetailModel>()
        rejects<WorkOpsReleaseEditModel>()
        rejects<WorkOpsReleaseBuildModel>()
        rejects<WorkOpsReleasePickerModel>()
        rejects<WorkOpsReleaseCiModel>()
        rejects<WorkOpsReleaseCiReadModel>()
        rejects<WorkOpsReleaseArtifactsModel>()
        rejects<WorkOpsReleaseNotesModel>()
        rejects<WorkOpsReleaseEnvironmentsModel>()
        rejects<WorkOpsReleaseEnvironmentsModel>("""{"id":"release"}""")
        rejects<WorkOpsApprovalModel>()
    }

    @Test fun `telemetry actions require release and application identities even for empty reads`() {
        rejects<WorkOpsReleaseHealthModel>()
        rejects<WorkOpsReleaseLiveModel>()
        rejects<WorkOpsReleaseStoreHealthModel>()
        rejects<WorkOpsReleaseTelemetrySourcesModel>()
        rejects<WorkOpsReleaseAppHealth>()
        rejects<WorkOpsReleaseServiceHealth>()
    }

    @Test fun `embedded task and document rows cannot silently synthesize their required identities and facts`() {
        rejects<WorkOpsTaskRow>()
        rejects<WorkOpsSpecRow>()
        rejects<WorkOpsQueryFilter>()
        rejects<WorkOpsBqlDiagnostic>()
        rejects<WorkOpsSprintChoice>()
        rejects<WorkOpsRequirementRow>()
        rejects<WorkOpsSpecContextRow>()
        rejects<WorkOpsSavedFilterRow>()
        rejects<WorkOpsFilterPreviewTask>()
    }

    @Test fun `embedded planning and board records require their inspected revisions mappings and ownership`() {
        rejects<WorkOpsProjectRecord>()
        rejects<WorkOpsProjectCatalogRow>()
        rejects<WorkOpsBoardChoice>()
        rejects<WorkOpsBoardSettingsDraft>()
        rejects<WorkOpsBoardColumn>()
        rejects<WorkOpsBoardCard>()
        rejects<WorkOpsBoardMove>()
        rejects<WorkOpsBoardSprint>()
        rejects<WorkOpsBoardTransition>()
        rejects<WorkOpsSprintRecord>()
        rejects<WorkOpsSprintTask>()
        rejects<WorkOpsSprintStartDraft>()
        rejects<WorkOpsSprintCloseDraft>()
        rejects<WorkOpsProjectDependencyRecord>()
        rejects<WorkOpsProjectRepositoryRecord>()
        rejects<WorkOpsProjectDeployConfig>()
        rejects<WorkOpsProjectIdentifier>()
    }

    @Test fun `embedded settings records require their catalog grant and calendar fields`() {
        rejects<WorkOpsWorkflowRecord>()
        rejects<WorkOpsWorkflowSchemeRecord>()
        rejects<WorkOpsWorkflowStateRecord>()
        rejects<WorkOpsWorkflowTransitionRecord>()
        rejects<WorkOpsSlaPolicyRecord>()
        rejects<WorkOpsSlaGoalRecord>()
        rejects<WorkOpsWorkingCalendarRecord>()
        rejects<WorkOpsGrantRecord>()
        rejects<WorkOpsEnvironmentRecord>()
        rejects<WorkOpsEnvironmentTypeRecord>()
        rejects<WorkOpsEnvironmentGrant>()
    }

    @Test fun `embedded release and telemetry records require the identities used to join their data`() {
        rejects<WorkOpsReleaseRecord>()
        rejects<WorkOpsReleaseVersionChoice>()
        rejects<WorkOpsReleaseComponentRecord>()
        rejects<WorkOpsReleaseComponentChoice>()
        rejects<WorkOpsReleaseArtifactRecord>()
        rejects<WorkOpsReleaseNoteRecord>()
        rejects<WorkOpsReleaseNoteVariant>()
        rejects<WorkOpsReleaseTelemetrySource>()
        rejects<WorkOpsReleaseLiveSource>()
        rejects<WorkOpsHealthCounter>()
        rejects<WorkOpsHealthSample>()
        rejects<WorkOpsStoreReview>()
        rejects<WorkOpsStoreObservation>()
        rejects<WorkOpsReleaseStoreHealthRow>()
        rejects<WorkOpsProjectStatusCount>()
        rejects<WorkOpsPromotionFamily>()
    }

    @Test fun `embedded notifications and approval gates require their original targets and prompt`() {
        rejects<WorkOpsNotificationRow>()
        rejects<WorkOpsNotificationSchemeRecord>()
        rejects<WorkOpsNotificationEventRecord>()
        rejects<WorkOpsApprovalRunRecord>()
        rejects<WorkOpsApprovalGateRecord>()
    }
}
