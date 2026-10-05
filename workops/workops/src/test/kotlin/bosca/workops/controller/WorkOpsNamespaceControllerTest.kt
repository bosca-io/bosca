package bosca.workops.controller

import kotlin.test.Test
import kotlin.test.assertSame

class WorkOpsNamespaceControllerTest {

    @Test
    fun `query namespace exposes every workops surface`() {
        val controller = WorkOpsQueryController()

        assertSame(WorkOpsPortfolios, controller.portfolios())
        assertSame(WorkOpsPrograms, controller.programs())
        assertSame(WorkOpsProjects, controller.projects())
        assertSame(WorkOpsTasks, controller.tasks())
        assertSame(WorkOpsStatuses, controller.statuses())
        assertSame(WorkOpsWorkflows, controller.workflows())
        assertSame(WorkOpsTaskLinks, controller.links())
        assertSame(WorkOpsBoards, controller.boards())
        assertSame(WorkOpsSprints, controller.sprints())
        assertSame(WorkOpsVersions, controller.versions())
        assertSame(WorkOpsComponents, controller.components())
        assertSame(WorkOpsLabels, controller.labels())
        assertSame(WorkOpsMilestones, controller.milestones())
        assertSame(WorkOpsTaskComments, controller.taskComments())
        assertSame(WorkOpsSavedFilters, controller.savedFilters())
        assertSame(WorkOpsAutomationQuery, controller.automation())
        assertSame(WorkOpsSlaQuery, controller.sla())
        assertSame(WorkOpsNotificationsQuery, controller.notifications())
        assertSame(WorkOpsAttachmentQuery, controller.attachments())
        assertSame(WorkOpsWorklogQuery, controller.worklogs())
        assertSame(WorkOpsCrossProjectQuery, controller.crossProject())
        assertSame(WorkOpsMultiRepoQuery, controller.multiRepo())
        assertSame(WorkOpsSpecs, controller.specs())
        assertSame(WorkOpsRequirements, controller.requirements())
        assertSame(WorkOpsSpecComments, controller.specComments())
        assertSame(WorkOpsRequirementComments, controller.requirementComments())
    }

    @Test
    fun `mutation namespace exposes every workops surface`() {
        val controller = WorkOpsMutationController()

        assertSame(WorkOpsPortfoliosMutation, controller.portfolios())
        assertSame(WorkOpsProgramsMutation, controller.programs())
        assertSame(WorkOpsProjectsMutation, controller.projects())
        assertSame(WorkOpsTasksMutation, controller.tasks())
        assertSame(WorkOpsTaskLinksMutation, controller.links())
        assertSame(WorkOpsBoardsMutation, controller.boards())
        assertSame(WorkOpsSprintsMutation, controller.sprints())
        assertSame(WorkOpsVersionsMutation, controller.versions())
        assertSame(WorkOpsComponentsMutation, controller.components())
        assertSame(WorkOpsLabelsMutation, controller.labels())
        assertSame(WorkOpsMilestonesMutation, controller.milestones())
        assertSame(WorkOpsTaskCommentsMutation, controller.taskComments())
        assertSame(WorkOpsSavedFiltersMutation, controller.savedFilters())
        assertSame(WorkOpsAutomationMutation, controller.automation())
        assertSame(WorkOpsSlaMutation, controller.sla())
        assertSame(WorkOpsNotificationsMutation, controller.notifications())
        assertSame(WorkOpsAttachmentMutation, controller.attachments())
        assertSame(WorkOpsWorklogMutation, controller.worklogs())
        assertSame(WorkOpsCrossProjectMutation, controller.crossProject())
        assertSame(WorkOpsMultiRepoMutation, controller.multiRepo())
        assertSame(WorkOpsTaskTypesMutation, controller.taskTypes())
        assertSame(WorkOpsPrioritiesMutation, controller.priorities())
        assertSame(WorkOpsResolutionsMutation, controller.resolutions())
        assertSame(WorkOpsStatusesMutation, controller.statuses())
        assertSame(WorkOpsTaskLinkTypesMutation, controller.linkTypes())
        assertSame(WorkOpsSpecsMutation, controller.specs())
        assertSame(WorkOpsRequirementsMutation, controller.requirements())
        assertSame(WorkOpsSpecCommentsMutation, controller.specComments())
        assertSame(WorkOpsRequirementCommentsMutation, controller.requirementComments())
    }
}
