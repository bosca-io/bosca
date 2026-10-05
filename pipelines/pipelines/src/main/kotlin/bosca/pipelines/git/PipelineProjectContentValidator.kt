package bosca.pipelines.git

import bosca.git.model.RepositoryContentType
import bosca.git.service.RepositoryContentValidationError
import bosca.git.service.RepositoryContentValidator
import bosca.pipelines.service.PipelineService
import bosca.serialization.UUID

/**
 * PIPELINE_PROJECT pre-receive validator. The hook hands us a snapshot of every file in the
 * proposed tree under the pipelines directory; we parse each YAML file and validate its graph
 * against the node registry.
 *
 * Symmetric to the validation path inside [PipelineGitSyncServiceImpl.pullFromGit] — both paths
 * feed the same parser and `PipelineService.validateGraph`, so a push that the pre-receive hook
 * accepts cannot later be rejected at pull time.
 *
 * Not registered by any composition root today: git pushes land on the dedicated
 * bosca-git-server, which loads no domain modules, so its pre-receive registry stays empty and
 * pull-time validation is the enforcement. This class is the PIPELINE_PROJECT entry point to
 * register if the git server ever gains a way to consult domain validators.
 */
class PipelineProjectContentValidator(
    private val pipelineService: PipelineService,
) : RepositoryContentValidator {

    private val parser = PipelineRepoFileParser()

    override val contentType: RepositoryContentType = RepositoryContentType.PIPELINE_PROJECT

    override val pathPrefixes: List<String> = listOf("${PipelineRepoLayout.PIPELINES_DIR}/")

    override suspend fun validate(
        repositoryId: UUID,
        files: Map<String, String>,
    ): List<RepositoryContentValidationError> {
        val errors = mutableListOf<RepositoryContentValidationError>()
        for ((path, content) in files) {
            when (val p = parser.parse(path, content)) {
                is ParsedPipelineFile.Parsed ->
                    pipelineService.validateGraph(p.file.graph)?.let {
                        errors += RepositoryContentValidationError(path, it)
                    }
                is ParsedPipelineFile.ParseError ->
                    errors += RepositoryContentValidationError(p.path, p.message)
                is ParsedPipelineFile.UnknownPath -> Unit // README etc. under pipelines/; ignore
            }
        }
        return errors
    }
}
