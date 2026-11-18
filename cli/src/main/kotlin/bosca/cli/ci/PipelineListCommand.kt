package bosca.cli.ci

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlin.uuid.Uuid

class PipelineListCommand : CiSubcommand("list") {
    override fun help(context: Context) = "List pipelines and their IDs for a repository"

    private val repoId by option("--repo-id", help = "Repository ID").required()

    override suspend fun execute(api: CiApi) {
        val pipelines = api.listPipelines(Uuid.parse(repoId))
        if (pipelines.isEmpty()) {
            echo("No pipelines found.")
            return
        }
        echo("PIPELINE ID  NAME  FILE")
        for (pipeline in pipelines) {
            echo("${pipeline.id}  ${pipeline.name}  ${pipeline.filePath}")
        }
    }
}
