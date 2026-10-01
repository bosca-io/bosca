package bosca.cli.workops

import bosca.cli.api.WorkOpsApi
import bosca.graphql.gen.WorkOpsPortfolioInput
import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import kotlin.uuid.Uuid

class PortfolioCommand : BoscaCliCommand(name = "portfolio") {
    override fun help(context: Context) = "Manage portfolios"
    override fun run() = Unit
}

class PortfolioListCommand : WorkOpsSubcommand("list") {
    override fun help(context: Context) = "List all portfolios"
    private val limit by option("--limit").int().default(50)

    override suspend fun execute(api: WorkOpsApi) {
        val portfolios = api.listPortfolios(limit = limit)
        if (portfolios.isEmpty()) {
            echo("No portfolios found.")
            return
        }
        echo("%-36s  %-10s  %-30s  %s".format("ID", "KEY", "NAME", "ARCHIVED"))
        echo("-".repeat(90))
        for (p in portfolios) {
            echo("%-36s  %-10s  %-30s  %s".format(
                p.id, p.key, p.name.take(30), p.archivedAt?.toString()?.take(10) ?: "-"
            ))
        }
    }
}

class PortfolioGetCommand : WorkOpsSubcommand("get") {
    override fun help(context: Context) = "Get a portfolio by ID or key"
    private val id by option("--id", help = "Portfolio UUID")
    private val key by option("--key", "-k", help = "Portfolio key")

    override suspend fun execute(api: WorkOpsApi) {
        val portfolio = when {
            id != null -> api.getPortfolio(Uuid.parse(id!!))
            key != null -> api.getPortfolioByKey(key!!)
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        if (portfolio == null) { echo("Portfolio not found."); return }
        echo("ID:          ${portfolio.id}")
        echo("Key:         ${portfolio.key}")
        echo("Name:        ${portfolio.name}")
        echo("Description: ${portfolio.description ?: "-"}")
        echo("Owner:       ${portfolio.ownerProfileId}")
        echo("Archived:    ${portfolio.archivedAt ?: "no"}")
        echo("Version:     ${portfolio.version}")
    }
}

class PortfolioCreateCommand : WorkOpsSubcommand("create") {
    override fun help(context: Context) = "Create a new portfolio"
    private val key by option("--key", "-k", help = "Unique uppercase key (2-10 chars)").required()
    private val name by option("--name", "-n", help = "Portfolio name").required()
    private val description by option("--description", "-d", help = "Description")
    private val ownerProfileId by option("--owner", help = "Owner profile UUID").required()

    override suspend fun execute(api: WorkOpsApi) {
        val result = api.createPortfolio(WorkOpsPortfolioInput(
            key = key,
            name = name,
            description = description,
            ownerProfileId = Uuid.parse(ownerProfileId),
        ))
        echo("Created portfolio: ${result.key} (${result.id})")
    }
}

class PortfolioArchiveCommand : WorkOpsSubcommand("archive") {
    override fun help(context: Context) = "Archive a portfolio"
    private val id by option("--id", help = "Portfolio UUID").required()
    private val expectedVersion by option("--version", help = "Expected version for optimistic lock").long().required()

    override suspend fun execute(api: WorkOpsApi) {
        val result = api.archivePortfolio(Uuid.parse(id), expectedVersion)
        echo("Archived portfolio: ${result.key}")
    }
}
