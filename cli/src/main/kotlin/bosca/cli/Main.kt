package bosca.cli

import bosca.cli.analytics.AnalyticsCommand
import bosca.cli.analytics.AnalyticsDashboardAddVisualizationCommand
import bosca.cli.analytics.AnalyticsDashboardCommand
import bosca.cli.analytics.AnalyticsDashboardCreateCommand
import bosca.cli.analytics.AnalyticsDashboardDeleteCommand
import bosca.cli.analytics.AnalyticsDashboardGetCommand
import bosca.cli.analytics.AnalyticsDashboardListCommand
import bosca.cli.analytics.AnalyticsDashboardPermissionGrantCommand
import bosca.cli.analytics.AnalyticsDashboardPermissionRevokeCommand
import bosca.cli.analytics.AnalyticsDashboardRemoveVisualizationCommand
import bosca.cli.analytics.AnalyticsDashboardRenderCommand
import bosca.cli.analytics.AnalyticsDashboardUpdateCommand
import bosca.cli.analytics.AnalyticsPermissionCommand
import bosca.cli.analytics.AnalyticsQueryCommand
import bosca.cli.analytics.AnalyticsQueryCreateCommand
import bosca.cli.analytics.AnalyticsQueryDeleteCommand
import bosca.cli.analytics.AnalyticsQueryExecuteCommand
import bosca.cli.analytics.AnalyticsQueryGetCommand
import bosca.cli.analytics.AnalyticsQueryListCommand
import bosca.cli.analytics.AnalyticsQueryPermissionGrantCommand
import bosca.cli.analytics.AnalyticsQueryPermissionRevokeCommand
import bosca.cli.analytics.AnalyticsQueryRefreshCommand
import bosca.cli.analytics.AnalyticsQueryUpdateCommand
import bosca.cli.analytics.AnalyticsSampleCommand
import bosca.cli.analytics.AnalyticsVisualizationCommand
import bosca.cli.analytics.AnalyticsVisualizationCreateCommand
import bosca.cli.analytics.AnalyticsVisualizationDeleteCommand
import bosca.cli.analytics.AnalyticsVisualizationGetCommand
import bosca.cli.analytics.AnalyticsVisualizationListCommand
import bosca.cli.analytics.AnalyticsVisualizationPermissionGrantCommand
import bosca.cli.analytics.AnalyticsVisualizationPermissionRevokeCommand
import bosca.cli.analytics.AnalyticsVisualizationRenderCommand
import bosca.cli.analytics.AnalyticsVisualizationUpdateCommand
import bosca.cli.a2a.KitA2AServerCommand
import bosca.cli.acp.KitAcpServerCommand
import bosca.cli.ci.AgentDeregisterCommand
import bosca.cli.ci.AgentListCommand
import bosca.cli.ci.AgentRegisterCommand
import bosca.cli.ci.AgentStartCommand
import bosca.cli.ci.ConfigureOrchestratorCommand
import bosca.cli.ci.CiAgentCommand
import bosca.cli.ci.CiCommand
import bosca.cli.ci.CiRunCommand
import bosca.cli.ci.CiSecretCommand
import bosca.cli.ci.RunCancelCommand
import bosca.cli.ci.RunListCommand
import bosca.cli.ci.RunLogsCommand
import bosca.cli.ci.RunRerunCommand
import bosca.cli.ci.RunLocalCommand
import bosca.cli.ci.RunTriggerCommand
import bosca.cli.ci.SecretDeleteCommand
import bosca.cli.ci.SecretListCommand
import bosca.cli.ci.SecretSetCommand
import bosca.cli.bml.BmlAuditCommand
import bosca.cli.bml.BmlCommand
import bosca.cli.bml.BmlCompileCommand
import bosca.cli.bml.BmlDevCommand
import bosca.cli.bml.BmlInitCommand
import bosca.cli.artifacts.ArtifactsCommand
import bosca.cli.artifacts.ArtifactsLoginCommand
import bosca.cli.git.GitCloneCommand
import bosca.cli.git.GitCommand
import bosca.cli.git.GitCredentialHelperCommand
import bosca.cli.git.GitFetchCommand
import bosca.cli.git.GitInfoCommand
import bosca.cli.git.GitListCommand
import bosca.cli.git.GitLoginCommand
import bosca.cli.git.GitMergeCommand
import bosca.cli.git.GitPrCommand
import bosca.cli.git.GitPrCreateCommand
import bosca.cli.git.GitPrListCommand
import bosca.cli.git.GitPrMergeCommand
import bosca.cli.git.GitPrViewCommand
import bosca.cli.git.GitPullCommand
import bosca.cli.git.GitPushCommand
import bosca.cli.git.GitUrlCommand
import bosca.cli.data.CollectionAddItemCommand
import bosca.cli.data.CollectionCommand
import bosca.cli.data.CollectionCreateCommand
import bosca.cli.data.CollectionDeleteCommand
import bosca.cli.data.CollectionEditCommand
import bosca.cli.data.CollectionGetCommand
import bosca.cli.data.CollectionListCommand
import bosca.cli.data.CollectionListItemsCommand
import bosca.cli.data.CollectionRemoveItemCommand
import bosca.cli.data.CollectionSetReadyCommand
import bosca.cli.data.DataCommand
import bosca.cli.data.InstallCommand
import bosca.cli.data.MetadataCommand
import bosca.cli.data.MetadataCreateCommand
import bosca.cli.data.MetadataDeleteCommand
import bosca.cli.data.MetadataEditCommand
import bosca.cli.data.MetadataGetCommand
import bosca.cli.data.MetadataListCommand
import bosca.cli.data.MetadataSetAttributesCommand
import bosca.cli.data.MetadataSetContentCommand
import bosca.cli.data.MetadataSetReadyCommand
import bosca.cli.data.RelationshipAddCommand
import bosca.cli.data.RelationshipCommand
import bosca.cli.data.RelationshipListCommand
import bosca.cli.data.RelationshipListInverseCommand
import bosca.cli.data.RelationshipRemoveCommand
import bosca.cli.data.SupplementaryAddCommand
import bosca.cli.data.SupplementaryCommand
import bosca.cli.data.SupplementaryDeleteCommand
import bosca.cli.data.SupplementaryListCommand
import bosca.cli.data.SupplementarySetContentCommand
import bosca.cli.data.TemplateCommand
import bosca.cli.data.TemplateCreateCollectionCommand
import bosca.cli.data.TemplateCreateDataCommand
import bosca.cli.data.TemplateCreateDocumentCommand
import bosca.cli.data.TemplateCreateGuideCommand
import bosca.cli.data.TemplateGetCommand
import bosca.cli.data.TemplateListCommand
import bosca.cli.localization.LocalizationCommand
import bosca.cli.localization.DownloadCommand
import bosca.cli.localization.StatusCommand
import bosca.cli.localization.SyncCommand
import bosca.cli.localization.UploadCommand
import bosca.cli.config.ConfigCommand
import bosca.cli.config.CliInvocation
import bosca.cli.config.LoginCommand
import bosca.cli.config.LogoutCommand
import bosca.cli.config.ProfileCommand
import bosca.cli.config.ProfileListCommand
import bosca.cli.config.ProfileRemoveCommand
import bosca.cli.config.ProfileShowCommand
import bosca.cli.config.ProfileUnsetCommand
import bosca.cli.config.ProfileUseCommand
import bosca.cli.config.requireValidProfileName
import bosca.cli.mcp.McpInstallCommand
import bosca.cli.mcp.McpServerCommand
import bosca.cli.service.ServiceCommand
import bosca.cli.service.ServiceInstallCommand
import bosca.cli.service.ServiceStatusCommand
import bosca.cli.service.ServiceUninstallCommand
import bosca.cli.swarm.SwarmCommand
import bosca.cli.swarm.SwarmEncryptCommand
import bosca.cli.swarm.SwarmDecryptCommand
import bosca.cli.swarm.SwarmBootstrapCommand
import bosca.cli.swarm.SwarmDeployCommand
import bosca.cli.swarm.SwarmInitCommand
import bosca.cli.swarm.SwarmRenderCommand
import bosca.cli.swarm.SwarmStatusCommand
import bosca.cli.swarm.SwarmSetupTokensCommand
import bosca.cli.tokens.TokenCommand
import bosca.cli.tokens.TokenCreateCommand
import bosca.cli.tokens.TokenDeleteCommand
import bosca.cli.tokens.TokenEditCommand
import bosca.cli.tokens.TokenGetCommand
import bosca.cli.tokens.TokenListCommand
import bosca.cli.tokens.TokenRevokeCommand
import bosca.cli.tokens.TokenScopesCommand
import bosca.cli.workops.BoardCommand
import bosca.cli.workops.BoardCreateCommand
import bosca.cli.workops.BoardGetCommand
import bosca.cli.workops.BoardListCommand
import bosca.cli.workops.LinkTypeListCommand
import bosca.cli.workops.SpecCommand
import bosca.cli.workops.SpecCommentCommand
import bosca.cli.workops.SpecCommentsListCommand
import bosca.cli.workops.SpecContextAddCommand
import bosca.cli.workops.SpecContextListCommand
import bosca.cli.workops.SpecContextRemoveCommand
import bosca.cli.workops.SpecCreateCommand
import bosca.cli.workops.SpecDeleteCommand
import bosca.cli.workops.SpecGenerateTasksCommand
import bosca.cli.workops.SpecGetCommand
import bosca.cli.workops.SpecHistoryCommand
import bosca.cli.workops.SpecListCommand
import bosca.cli.workops.SpecTransitionCommand
import bosca.cli.workops.SpecUpdateCommand
import bosca.cli.workops.PortfolioArchiveCommand
import bosca.cli.workops.PortfolioCommand
import bosca.cli.workops.PortfolioCreateCommand
import bosca.cli.workops.PortfolioGetCommand
import bosca.cli.workops.PortfolioListCommand
import bosca.cli.workops.PriorityListCommand
import bosca.cli.workops.ProgramCommand
import bosca.cli.workops.ProgramCreateCommand
import bosca.cli.workops.ProgramGetCommand
import bosca.cli.workops.ProgramListCommand
import bosca.cli.workops.ProjectCommand
import bosca.cli.workops.ProjectCreateCommand
import bosca.cli.workops.ProjectGetCommand
import bosca.cli.workops.ProjectListCommand
import bosca.cli.workops.RefDataCommand
import bosca.cli.workops.ResolutionListCommand
import bosca.cli.workops.SprintCommand
import bosca.cli.workops.SprintCreateCommand
import bosca.cli.workops.SprintGetCommand
import bosca.cli.workops.SprintListCommand
import bosca.cli.workops.StatusListCommand
import bosca.cli.workops.TaskCommand
import bosca.cli.workops.TaskCommentCommand
import bosca.cli.workops.TaskCommentsListCommand
import bosca.cli.workops.TaskCreateCommand
import bosca.cli.workops.TaskDeleteCommand
import bosca.cli.workops.TaskGetCommand
import bosca.cli.workops.TaskHistoryCommand
import bosca.cli.workops.TaskListCommand
import bosca.cli.workops.TaskSearchCommand
import bosca.cli.workops.TaskTransitionCommand
import bosca.cli.workops.TaskTransitionsCommand
import bosca.cli.workops.TaskTypeListCommand
import bosca.cli.workops.TaskAddAffectedProjectCommand
import bosca.cli.workops.TaskRemoveAffectedProjectCommand
import bosca.cli.workops.TaskUpdateCommand
import bosca.cli.workops.WorkOpsCommand
import bosca.cli.workops.WorkflowGetCommand
import bosca.cli.workops.WorkflowListCommand
import bosca.cli.workops.WorklogAddCommand
import bosca.cli.workops.WorklogCommand
import bosca.cli.workops.WorklogListCommand
import bosca.cli.update.UpdateChecker
import bosca.cli.update.VersionCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption

/**
 * Unified Bosca CLI — a single entry point for the entire framework
 * toolchain: localization, data provisioning,
 * work operations, and MCP server.
 */
class BoscaCommand : BoscaCliCommand(name = "bosca") {
    private val profile by option(
        "--profile",
        envvar = "BOSCA_PROFILE",
        help = "Use a saved profile for this invocation without changing the active profile",
    )

    init {
        // `bosca --version` prints "bosca version <X>" and exits. The version is
        // baked into the binary at build time (see bosca.cli.Version).
        versionOption(Version.current)
    }

    override fun help(context: Context) =
        "Bosca framework CLI — analytics, content management, work operations, localization, and data provisioning."
    override fun run() {
        profile?.let(::requireValidProfileName)
        CliInvocation.selectProfile(profile)
    }
}

fun main(args: Array<String>) {
    BoscaCommand()
    .subcommands(
        VersionCommand(),
        LoginCommand(),
        LogoutCommand(),
        ConfigCommand(),
        ProfileCommand().subcommands(
            ProfileListCommand(),
            ProfileShowCommand(),
            ProfileUseCommand(),
            ProfileUnsetCommand(),
            ProfileRemoveCommand(),
        ),
        McpServerCommand(),
        McpInstallCommand(),
        KitA2AServerCommand(),
        KitAcpServerCommand(),
        AnalyticsCommand().subcommands(
            AnalyticsQueryCommand().subcommands(
                AnalyticsQueryListCommand(),
                AnalyticsQueryGetCommand(),
                AnalyticsQueryCreateCommand(),
                AnalyticsQueryUpdateCommand(),
                AnalyticsQueryDeleteCommand(),
                AnalyticsQueryExecuteCommand(),
                AnalyticsQueryRefreshCommand(),
                AnalyticsPermissionCommand().subcommands(
                    AnalyticsQueryPermissionGrantCommand(),
                    AnalyticsQueryPermissionRevokeCommand(),
                ),
            ),
            AnalyticsVisualizationCommand().subcommands(
                AnalyticsVisualizationListCommand(),
                AnalyticsVisualizationGetCommand(),
                AnalyticsVisualizationCreateCommand(),
                AnalyticsVisualizationUpdateCommand(),
                AnalyticsVisualizationDeleteCommand(),
                AnalyticsVisualizationRenderCommand(),
                AnalyticsPermissionCommand().subcommands(
                    AnalyticsVisualizationPermissionGrantCommand(),
                    AnalyticsVisualizationPermissionRevokeCommand(),
                ),
            ),
            AnalyticsDashboardCommand().subcommands(
                AnalyticsDashboardListCommand(),
                AnalyticsDashboardGetCommand(),
                AnalyticsDashboardCreateCommand(),
                AnalyticsDashboardUpdateCommand(),
                AnalyticsDashboardDeleteCommand(),
                AnalyticsDashboardAddVisualizationCommand(),
                AnalyticsDashboardRemoveVisualizationCommand(),
                AnalyticsDashboardRenderCommand(),
                AnalyticsPermissionCommand().subcommands(
                    AnalyticsDashboardPermissionGrantCommand(),
                    AnalyticsDashboardPermissionRevokeCommand(),
                ),
            ),
            AnalyticsSampleCommand(),
        ),
        WorkOpsCommand().subcommands(
            PortfolioCommand().subcommands(
                PortfolioListCommand(),
                PortfolioGetCommand(),
                PortfolioCreateCommand(),
                PortfolioArchiveCommand(),
            ),
            ProgramCommand().subcommands(
                ProgramListCommand(),
                ProgramGetCommand(),
                ProgramCreateCommand(),
            ),
            ProjectCommand().subcommands(
                ProjectListCommand(),
                ProjectGetCommand(),
                ProjectCreateCommand(),
            ),
            TaskCommand().subcommands(
                TaskListCommand(),
                TaskGetCommand(),
                TaskCreateCommand(),
                TaskUpdateCommand(),
                TaskTransitionCommand(),
                TaskTransitionsCommand(),
                TaskHistoryCommand(),
                TaskSearchCommand(),
                TaskDeleteCommand(),
                TaskCommentCommand(),
                TaskCommentsListCommand(),
                TaskAddAffectedProjectCommand(),
                TaskRemoveAffectedProjectCommand(),
            ),
            SpecCommand().subcommands(
                SpecListCommand(),
                SpecGetCommand(),
                SpecCreateCommand(),
                SpecUpdateCommand(),
                SpecTransitionCommand(),
                SpecDeleteCommand(),
                SpecHistoryCommand(),
                SpecContextListCommand(),
                SpecContextAddCommand(),
                SpecContextRemoveCommand(),
                SpecCommentCommand(),
                SpecCommentsListCommand(),
                SpecGenerateTasksCommand(),
            ),
            BoardCommand().subcommands(
                BoardListCommand(),
                BoardGetCommand(),
                BoardCreateCommand(),
            ),
            SprintCommand().subcommands(
                SprintListCommand(),
                SprintGetCommand(),
                SprintCreateCommand(),
            ),
            WorklogCommand().subcommands(
                WorklogListCommand(),
                WorklogAddCommand(),
            ),
            RefDataCommand().subcommands(
                StatusListCommand(),
                TaskTypeListCommand(),
                PriorityListCommand(),
                ResolutionListCommand(),
                WorkflowListCommand(),
                WorkflowGetCommand(),
                LinkTypeListCommand(),
            ),
        ),
        BmlCommand().subcommands(
            BmlInitCommand(),
            BmlCompileCommand(),
            BmlDevCommand(),
            BmlAuditCommand(),
            bosca.cli.bml.i18n.BmlI18nCommand().subcommands(
                bosca.cli.bml.i18n.I18nExtractCommand(),
                bosca.cli.bml.i18n.I18nPushCommand(),
                bosca.cli.bml.i18n.I18nStatusCommand(),
            ),
        ),
        LocalizationCommand().subcommands(
            UploadCommand(),
            DownloadCommand(),
            StatusCommand(),
            SyncCommand(),
        ),
        DataCommand().subcommands(
            InstallCommand(),
            TemplateCommand().subcommands(
                TemplateListCommand(),
                TemplateGetCommand(),
                TemplateCreateDocumentCommand(),
                TemplateCreateGuideCommand(),
                TemplateCreateDataCommand(),
                TemplateCreateCollectionCommand(),
            ),
            MetadataCommand().subcommands(
                MetadataListCommand(),
                MetadataGetCommand(),
                MetadataCreateCommand(),
                MetadataEditCommand(),
                MetadataDeleteCommand(),
                MetadataSetContentCommand(),
                MetadataSetAttributesCommand(),
                MetadataSetReadyCommand(),
            ),
            CollectionCommand().subcommands(
                CollectionListCommand(),
                CollectionGetCommand(),
                CollectionCreateCommand(),
                CollectionEditCommand(),
                CollectionDeleteCommand(),
                CollectionListItemsCommand(),
                CollectionAddItemCommand(),
                CollectionRemoveItemCommand(),
                CollectionSetReadyCommand(),
            ),
            SupplementaryCommand().subcommands(
                SupplementaryListCommand(),
                SupplementaryAddCommand(),
                SupplementaryDeleteCommand(),
                SupplementarySetContentCommand(),
            ),
            RelationshipCommand().subcommands(
                RelationshipListCommand(),
                RelationshipListInverseCommand(),
                RelationshipAddCommand(),
                RelationshipRemoveCommand(),
            ),
        ),
        CiCommand().subcommands(
            CiAgentCommand().subcommands(
                AgentRegisterCommand(),
                AgentListCommand(),
                AgentDeregisterCommand(),
                AgentStartCommand(),
                ConfigureOrchestratorCommand(),
            ),
            CiRunCommand().subcommands(
                RunLocalCommand(),
                RunTriggerCommand(),
                RunListCommand(),
                RunCancelCommand(),
                RunRerunCommand(),
                RunLogsCommand(),
            ),
            CiSecretCommand().subcommands(
                SecretSetCommand(),
                SecretListCommand(),
                SecretDeleteCommand(),
            ),
        ),
        ArtifactsCommand().subcommands(
            ArtifactsLoginCommand(),
        ),
        GitCommand().subcommands(
            GitLoginCommand(),
            GitCloneCommand(),
            GitListCommand(),
            GitInfoCommand(),
            GitUrlCommand(),
            GitPushCommand(),
            GitPullCommand(),
            GitFetchCommand(),
            GitMergeCommand(),
            GitCredentialHelperCommand(),
            GitPrCommand().subcommands(
                GitPrListCommand(),
                GitPrViewCommand(),
                GitPrCreateCommand(),
                GitPrMergeCommand(),
            ),
        ),
        TokenCommand().subcommands(
            TokenListCommand(),
            TokenGetCommand(),
            TokenCreateCommand(),
            TokenRevokeCommand(),
            TokenDeleteCommand(),
            TokenEditCommand(),
            TokenScopesCommand(),
        ),
        ServiceCommand().subcommands(
            ServiceInstallCommand(),
            ServiceUninstallCommand(),
            ServiceStatusCommand(),
        ),
        SwarmCommand().subcommands(
            SwarmInitCommand(),
            SwarmEncryptCommand(),
            SwarmDecryptCommand(),
            SwarmRenderCommand(),
            SwarmBootstrapCommand(),
            SwarmDeployCommand(),
            SwarmSetupTokensCommand(),
            SwarmStatusCommand(),
        ),
    )
    .main(args)

    // After a command completes successfully, surface a throttled, best-effort
    // "update available" notice (interactive terminals only; never blocks or
    // fails the command). Help/version/error paths exit inside .main() above and
    // never reach here, which is intentional — no nudge on those.
    UpdateChecker.maybeNotify()
}
