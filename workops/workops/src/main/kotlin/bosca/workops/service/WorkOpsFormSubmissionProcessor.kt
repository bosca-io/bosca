package bosca.workops.service

import bosca.forms.model.FormSchema
import bosca.forms.model.FormSchemaType
import bosca.forms.model.FormSubmission
import bosca.forms.model.FormSubmissionInput
import bosca.forms.service.FormSubmissionProcessor
import bosca.forms.service.SubmittedForm
import bosca.profile.model.Profile
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.task.CreateTaskInput
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Creates a Work Ops task when a form of type [FormSchemaType.WORK_OPS]
 * is submitted. The form schema's `configuration` JSON drives the
 * mapping:
 *
 * ```json
 * {
 *   "workOps": {
 *     "projectId": "uuid-of-target-project",
 *     "taskTypeId": "optional-task-type-uuid",
 *     "fieldMappings": {
 *       "summary": "form_field_key_for_summary",
 *       "descriptionMarkdown": "form_field_key_for_description"
 *     }
 *   }
 * }
 * ```
 *
 * Every submission value whose key is **not** mapped to a known task
 * field is stored as a task custom-field value, so the form builder
 * can add arbitrary fields without a code change.
 */
class WorkOpsFormSubmissionProcessor(
    private val taskService: TaskService,
    private val projectService: ProjectService,
) : FormSubmissionProcessor {

    override val type: FormSchemaType = FormSchemaType.WORK_OPS

    override suspend fun process(profileId: UUID, submission: FormSubmissionInput, schema: FormSchema): SubmittedForm {
        val configuration = schema.configuration
            ?: error("WORK_OPS form ${schema.id} has no workOps configuration block — skipping task creation")
        val config = configuration.jsonObject["workOps"]?.jsonObject
            ?: error("WORK_OPS form ${schema.id} has no workOps configuration block — skipping task creation")

        val attrs = submission.attributes.jsonObject
        val configuredProjectId = config["projectId"]
        val submittedProjectId = attrs["projectId"]
        val projectIdStr = when {
            configuredProjectId != null -> configuredProjectId.jsonPrimitive.content
            submittedProjectId != null -> submittedProjectId.jsonPrimitive.content
            else -> error("WORK_OPS form ${schema.id} has no projectId in configuration or submission attributes")
        }
        val projectId = UUID.parse(projectIdStr)
        val project = projectService.getById(projectId)
        if (project == null) {
            log.error("WORK_OPS form ${schema.id} references non-existent project $projectId")
        }

        val configuredFieldMappings = config["fieldMappings"]
        val fieldMappings = if (configuredFieldMappings == null) {
            emptyMap()
        } else {
            configuredFieldMappings.jsonObject.mapValues { it.value.jsonPrimitive.content }
        }

        fun resolveUuid(taskField: String): UUID? {
            val formKey = fieldMappings[taskField] ?: taskField
            val configured = config[taskField]
            if (configured != null) return UUID.parse(configured.jsonPrimitive.content)
            val submitted = attrs[formKey] ?: return null
            val content = submitted.jsonPrimitive.content
            return if (content.isBlank()) null else UUID.parse(content)
        }

        fun resolveString(taskField: String): String? {
            val formKey = fieldMappings[taskField] ?: taskField
            val submitted = attrs[formKey] ?: return null
            return submitted.jsonPrimitive.content
        }

        fun resolveDateTime(taskField: String): OffsetDateTime? {
            val formKey = fieldMappings[taskField] ?: taskField
            val submitted = attrs[formKey] ?: return null
            val content = submitted.jsonPrimitive.content
            return if (content.isBlank()) null else OffsetDateTime.parse(content)
        }

        val summary = resolveString("summary")
        if (summary.isNullOrBlank()) {
            val summaryKey = fieldMappings["summary"] ?: "summary"
            error("WORK_OPS form submission has no summary (looked for field '${summaryKey}')")
        }

        val allMappedKeys = buildSet {
            for (taskField in listOf("projectId", "taskTypeId", "statusId", "priorityId",
                "summary", "descriptionMarkdown", "assigneeProfileId", "dueDate", "startDate")) {
                add(fieldMappings[taskField] ?: taskField)
            }
        }
        val customFields = attrs.filterKeys { it !in allMappedKeys }
            .mapValues<String, JsonElement, JsonElement> { it.value }

        val input = CreateTaskInput(
            projectId = projectId,
            taskTypeId = resolveUuid("taskTypeId"),
            statusId = resolveUuid("statusId"),
            priorityId = resolveUuid("priorityId"),
            summary = summary,
            descriptionMarkdown = resolveString("descriptionMarkdown"),
            assigneeProfileId = resolveUuid("assigneeProfileId"),
            dueDate = resolveDateTime("dueDate"),
            startDate = resolveDateTime("startDate"),
            customFields = customFields,
        )

        val task = taskService.create(
            input = input,
            actingPrincipalId = profileId,
            actingProfileId = profileId,
            reporterProfileId = profileId,
        )

        log.info("Created Work Ops task from form submission ${task.id} in project ${project?.key}")

        return SubmittedForm(task.id, FormSchemaType.WORK_OPS)
    }

    companion object {
        private val log = LoggerFactory.getLogger(WorkOpsFormSubmissionProcessor::class.java)
    }
}
