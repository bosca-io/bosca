package bosca.server.installer

import bosca.communications.model.NotificationTypeKeys
import bosca.git.model.PullRequestEvent
import bosca.git.model.RefUpdateEvent
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.builtin.ConditionNode
import bosca.pipelines.builtin.JsonataNode
import bosca.pipelines.builtin.SendEmailTemplateNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.service.PipelineService
import bosca.serialization.UUID
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Seeds editable event-to-email pipelines for Git collaboration activity. The Git events carry
 * recipient profile ids and domain detail; each graph gates an empty audience, shapes the typed
 * BML payload with JSONata, and sends through the ordinary preference-aware communications path.
 *
 * Pipelines are created only when their stable names are absent. Existing pipelines retain operator
 * edits; installers only migrate obsolete first-party message-project references.
 */
class DefaultGitEmailPipelinesInstaller(
    private val pipelineService: PipelineService,
    applicationUrl: String,
) : PackageInstaller {

    override val version: String = VERSION

    private val appUrlLiteral = Json.encodeToString(String.serializer(), applicationUrl.trimEnd('/'))

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val existingByName = pipelineService.getAll().associateBy { it.name }
        for (spec in specs()) {
            val existing = existingByName[spec.name]
            if (existing != null) {
                if (pipelineService.migrateDefaultEmailPipeline(existing)) {
                    log.info("migrated default Git email pipeline '{}' to the Bosca message project", spec.name)
                } else {
                    log.info("default Git email pipeline '{}' already present; skipping", spec.name)
                }
                continue
            }
            val pipeline = spec.pipeline()
            log.info("creating default Git email pipeline '{}' for {}", spec.name, spec.eventType)
            pipelineService.save(
                id = UUID.NIL,
                name = pipeline.name,
                description = pipeline.description,
                acceptedInputType = pipeline.acceptedInputType,
                tags = pipeline.tags,
                triggered = true,
                version = 0,
                graph = pipelineService.graphAsJsonElement(pipeline),
            )
        }
    }

    private fun specs(): List<Spec> = listOf(
        Spec(
            name = PULL_REQUEST_PIPELINE,
            description = "Sends preference-aware email for pull-request lifecycle, review, comment, assignment, and source-update events.",
            eventType = requireNotNull(PullRequestEvent::class.qualifiedName),
            template = "git-pull-request",
            payloadType = "email:bosca-messages/git-pull-request",
            payloadExpression = """
                {
                  "repositoryName": repositoryName,
                  "number": number,
                  "title": title,
                  "action": action,
                  "actorName": actorName,
                  "sourceBranch": sourceBranch,
                  "targetBranch": targetBranch,
                  "body": body,
                  "filePath": filePath,
                  "lineNumber": lineNumber,
                  "reviewStatus": reviewStatus,
                  "taskKeys": taskKeys,
                  "pullRequestUrl": $appUrlLiteral & "/git/pulls/" & pullRequestId & "?repo=" & repositoryId & "&number=" & ${'$'}string(number)
                }
            """.trimIndent(),
        ),
        Spec(
            name = REF_UPDATE_PIPELINE,
            description = "Sends preference-aware email when a repository branch or tag is created, updated, or deleted.",
            eventType = requireNotNull(RefUpdateEvent::class.qualifiedName),
            template = "git-ref-update",
            payloadType = "email:bosca-messages/git-ref-update",
            payloadExpression = """
                {
                  "repositoryName": repositoryName,
                  "refName": refName,
                  "kind": kind,
                  "action": action,
                  "beforeSha": beforeSha,
                  "afterSha": afterSha,
                  "taskKeys": taskKeys,
                  "commitMessages": commitMessages,
                  "repositoryUrl": $appUrlLiteral & "/git/repositories/" & repositoryId
                }
            """.trimIndent(),
        ),
    )

    private data class Spec(
        val name: String,
        val description: String,
        val eventType: String,
        val template: String,
        val payloadType: String,
        val payloadExpression: String,
    ) {
        fun pipeline(): Pipeline = Pipeline(
            id = UUID.NIL,
            name = name,
            description = description,
            acceptedInputType = eventType,
            tags = listOf("Git", "Email", "Notifications"),
            triggered = true,
            nodes = nodes(),
            edges = edges(),
        )

        private fun nodes(): List<PipelineNode> = listOf(
            InputNode(id = INPUT, acceptedType = eventType, position = NodePosition(40.0, 140.0)),
            ConditionNode(
                id = HAS_RECIPIENTS,
                name = "Has recipients",
                expression = "${'$'}count(recipientIds) > 0",
                position = NodePosition(250.0, 140.0),
            ),
            JsonataNode(
                id = RECIPIENTS,
                name = "Recipients",
                expression = "recipientIds",
                outputKind = SlotKind.ARRAY,
                position = NodePosition(480.0, 70.0),
            ),
            JsonataNode(
                id = PAYLOAD,
                name = "Email payload",
                expression = payloadExpression,
                outputKind = SlotKind.OBJECT,
                outputType = payloadType,
                position = NodePosition(480.0, 210.0),
            ),
            SendEmailTemplateNode(
                id = SEND,
                name = "Send Git activity email",
                project = MESSAGE_PROJECT,
                template = template,
                notificationType = NotificationTypeKeys.GIT_ACTIVITY,
                position = NodePosition(760.0, 140.0),
            ),
        )

        private fun edges(): List<PipelineEdge> = listOf(
            PipelineEdge(id = "e1", source = INPUT, target = HAS_RECIPIENTS),
            PipelineEdge(id = "e2", source = HAS_RECIPIENTS, sourcePort = "true", target = RECIPIENTS),
            PipelineEdge(id = "e3", source = HAS_RECIPIENTS, sourcePort = "true", target = PAYLOAD),
            PipelineEdge(id = "e4", source = RECIPIENTS, target = SEND, targetPort = "recipients"),
            PipelineEdge(id = "e5", source = PAYLOAD, target = SEND, targetPort = "payload"),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(DefaultGitEmailPipelinesInstaller::class.java)

        const val NAME = "default-git-email-pipelines"
        const val VERSION = "1.1.0"
        const val PULL_REQUEST_PIPELINE = "Email Git Activity — Pull Requests"
        const val REF_UPDATE_PIPELINE = "Email Git Activity — Branches and Tags"

        val TEMPLATE_KEYS = setOf("git-pull-request", "git-ref-update")

        private const val MESSAGE_PROJECT = "bosca-messages"
        private const val INPUT = "input"
        private const val HAS_RECIPIENTS = "hasRecipients"
        private const val RECIPIENTS = "recipients"
        private const val PAYLOAD = "payload"
        private const val SEND = "send"
    }
}
