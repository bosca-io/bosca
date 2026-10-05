package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

object WorkOps

@TypeController
class WorkOpsQueryController : GraphQLController<WorkOps> {
    @Field fun portfolios() = WorkOpsPortfolios
    @Field fun programs() = WorkOpsPrograms
    @Field fun projects() = WorkOpsProjects
    @Field fun tasks() = WorkOpsTasks
    @Field fun statuses() = WorkOpsStatuses
    @Field fun workflows() = WorkOpsWorkflows
    @Field fun links() = WorkOpsTaskLinks
    @Field fun boards() = WorkOpsBoards
    @Field fun sprints() = WorkOpsSprints
    @Field fun versions() = WorkOpsVersions
    @Field fun components() = WorkOpsComponents
    @Field fun labels() = WorkOpsLabels
    @Field fun milestones() = WorkOpsMilestones
    @Field fun taskComments() = WorkOpsTaskComments
    @Field fun savedFilters() = WorkOpsSavedFilters
    @Field fun automation() = WorkOpsAutomationQuery
    @Field fun sla() = WorkOpsSlaQuery
    @Field fun notifications() = WorkOpsNotificationsQuery
    @Field fun attachments() = WorkOpsAttachmentQuery
    @Field fun worklogs() = WorkOpsWorklogQuery
    @Field fun crossProject() = WorkOpsCrossProjectQuery
    @Field fun multiRepo() = WorkOpsMultiRepoQuery
    @Field fun specs() = WorkOpsSpecs
    @Field fun requirements() = WorkOpsRequirements
    @Field fun specComments() = WorkOpsSpecComments
    @Field fun requirementComments() = WorkOpsRequirementComments
}

object WorkOpsMutationsRoot

@TypeController(type = "WorkOpsMutations")
class WorkOpsMutationController : GraphQLController<WorkOpsMutationsRoot> {
    @Field fun portfolios() = WorkOpsPortfoliosMutation
    @Field fun programs() = WorkOpsProgramsMutation
    @Field fun projects() = WorkOpsProjectsMutation
    @Field fun tasks() = WorkOpsTasksMutation
    @Field fun links() = WorkOpsTaskLinksMutation
    @Field fun boards() = WorkOpsBoardsMutation
    @Field fun sprints() = WorkOpsSprintsMutation
    @Field fun versions() = WorkOpsVersionsMutation
    @Field fun components() = WorkOpsComponentsMutation
    @Field fun labels() = WorkOpsLabelsMutation
    @Field fun milestones() = WorkOpsMilestonesMutation
    @Field fun taskComments() = WorkOpsTaskCommentsMutation
    @Field fun savedFilters() = WorkOpsSavedFiltersMutation
    @Field fun automation() = WorkOpsAutomationMutation
    @Field fun sla() = WorkOpsSlaMutation
    @Field fun notifications() = WorkOpsNotificationsMutation
    @Field fun attachments() = WorkOpsAttachmentMutation
    @Field fun worklogs() = WorkOpsWorklogMutation
    @Field fun crossProject() = WorkOpsCrossProjectMutation
    @Field fun multiRepo() = WorkOpsMultiRepoMutation
    @Field fun taskTypes() = WorkOpsTaskTypesMutation
    @Field fun priorities() = WorkOpsPrioritiesMutation
    @Field fun resolutions() = WorkOpsResolutionsMutation
    @Field fun statuses() = WorkOpsStatusesMutation
    @Field fun linkTypes() = WorkOpsTaskLinkTypesMutation
    @Field fun specs() = WorkOpsSpecsMutation
    @Field fun requirements() = WorkOpsRequirementsMutation
    @Field fun specComments() = WorkOpsSpecCommentsMutation
    @Field fun requirementComments() = WorkOpsRequirementCommentsMutation
}
