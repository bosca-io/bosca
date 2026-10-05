package bosca.profile.installer

import bosca.forms.model.FormSchemaInput
import bosca.forms.service.FormSchemaService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.profile.service.ProfileService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Creates a form schema for every profile attribute type that does not
 * already have one, then links the schema back to the attribute type.
 *
 * Each generated form contains one field whose property matches the
 * attribute model and whose control type matches the attribute's purpose.
 */
class ProfileAttributeFormSchemaInstaller(
    private val profileService: ProfileService,
    private val formSchemaService: FormSchemaService,
) : PackageInstaller {

    override val version: String = "1.1.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val attributeTypes = profileService.getAttributeTypes()

        for (type in attributeTypes) {
            val spec = specFor(type.id)
            val schemaKey = "form.${type.id}"
            val linkedSchema = type.formSchemaId?.let { formSchemaService.getById(it) }
            if (linkedSchema != null && linkedSchema.key != schemaKey) {
                continue
            }

            val formSchema = formSchemaService.save(
                FormSchemaInput(
                    key = schemaKey,
                    name = type.name,
                    description = "Form for profile attribute: ${type.description}",
                    schema = buildJsonSchema(spec),
                    uiSchema = buildUiSchema(spec),
                    public = true,
                )
            )
            if (!formSchema.published) {
                formSchemaService.setPublished(formSchema.id, true)
            }

            if (type.formSchemaId != formSchema.id) {
                profileService.editAttributeType(
                    ProfileAttributeTypeInput(
                        id = type.id,
                        name = type.name,
                        description = type.description,
                        visibility = type.visibility,
                        protected = type.protected,
                        formSchemaId = formSchema.id,
                    )
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Per-attribute specifications
    // -----------------------------------------------------------------------

    private data class FieldSpec(
        val label: String,
        val property: String = "value",
        val jsonType: String = "string",
        val control: String = "text-input",
        val format: String? = null,
        val rows: Int? = null,
        val placeholder: String? = null,
    )

    private fun specFor(attributeId: String): FieldSpec = when (attributeId) {
        "bosca.profiles.name" -> FieldSpec(label = "Name", property = "name", placeholder = "Full name")
        "bosca.profiles.name.given" -> FieldSpec(label = "Given Name", property = "name", placeholder = "Given name")
        "bosca.profiles.name.family" -> FieldSpec(label = "Family Name", property = "name", placeholder = "Family name")
        "bosca.profiles.email" -> FieldSpec(label = "Email", property = "email", format = "email", placeholder = "email@example.com")
        "bosca.profiles.locale" -> FieldSpec(label = "Preferred Locale", property = "locale", placeholder = "e.g. en-US")
        "bosca.profiles.timezone" -> FieldSpec(label = "Preferred Timezone", placeholder = "e.g. America/New_York")
        "bosca.profiles.bio" -> FieldSpec(label = "Bio", control = "textarea", rows = 4, placeholder = "Tell us about yourself")
        "bosca.profiles.comment.disabled" -> FieldSpec(label = "Commenting Disabled", jsonType = "boolean", control = "switch")
        "bosca.profiles.comment.moderator" -> FieldSpec(label = "Moderator", jsonType = "boolean", control = "switch")
        "bosca.profiles.organization.church.tradition" -> FieldSpec(label = "Tradition", placeholder = "Church tradition")
        "bosca.profiles.organization.church.audience.size" -> FieldSpec(label = "Audience Size", placeholder = "Audience size")
        "bosca.profiles.country" -> FieldSpec(label = "Country", placeholder = "Country")
        else -> FieldSpec(label = attributeId.substringAfterLast('.').replaceFirstChar { it.uppercase() })
    }

    // -----------------------------------------------------------------------
    // Schema builders
    // -----------------------------------------------------------------------

    private fun buildJsonSchema(spec: FieldSpec): JsonObject {
        val valueProps = buildMap<String, JsonPrimitive> {
            put("type", JsonPrimitive(spec.jsonType))
            spec.format?.let { put("format", JsonPrimitive(it)) }
        }

        return JsonObject(
            mapOf(
                "\$schema" to JsonPrimitive("https://json-schema.org/draft/2020-12/schema"),
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(spec.property to JsonObject(valueProps))
                ),
                "required" to JsonArray(listOf(JsonPrimitive(spec.property))),
            )
        )
    }

    private fun buildUiSchema(spec: FieldSpec): JsonObject {
        val fieldProps = buildMap<String, Any> {
            put("type", "field")
            put("property", spec.property)
            put("control", spec.control)
            put("label", spec.label)
            spec.placeholder?.let { put("placeholder", it) }
            spec.rows?.let { put("rows", it) }
        }.mapValues { (_, v) ->
            when (v) {
                is String -> JsonPrimitive(v)
                is Int -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        }

        return JsonObject(
            mapOf(
                "version" to JsonPrimitive(1),
                "layout" to JsonArray(
                    listOf(JsonObject(fieldProps))
                ),
            )
        )
    }
}
