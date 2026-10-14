package bosca.workops.service

import bosca.workops.model.guidance.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** SKILL portability changes guide text only; frontmatter never supplies executable policy. */
internal object WorkOpsSkillCodec {
    fun validate(guide: WorkOpsGuideInput) {
        require(guide.name.length <= 64 && Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(guide.name)) { "Skill name must use lowercase letters, digits, and single hyphens (maximum 64 characters)" }
        require(guide.description.isNotBlank() && guide.markdown.isNotBlank()) { "Skill description and body must not be blank" }
    }

    fun decode(phase: WorkOpsGuidancePhase, skill: String): WorkOpsGuideInput {
        val normalized = skill.replace("\r\n", "\n")
        require(normalized.startsWith("---\n")) { "SKILL.md needs YAML frontmatter" }
        val end = normalized.indexOf("\n---\n", 4)
        require(end >= 0) { "SKILL.md needs a closing frontmatter delimiter" }
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false }
        val fields = try {
            Yaml(SafeConstructor(options)).load<Any>(normalized.substring(4, end)) as? Map<*, *>
                ?: throw IllegalArgumentException("Skill frontmatter must be a mapping")
        } catch (e: org.yaml.snakeyaml.error.YAMLException) {
            throw IllegalArgumentException("Invalid skill frontmatter", e)
        }
        val name = fields["name"] as? String ?: throw IllegalArgumentException("Skill name is required")
        val description = fields["description"] as? String ?: throw IllegalArgumentException("Skill description is required")
        return WorkOpsGuideInput(phase, name, description, normalized.substring(end + 5).trim()).also(::validate)
    }

    fun encode(guide: WorkOpsGuide): String {
        val quote = { value: String -> Json.encodeToString(String.serializer(), value) }
        return "---\nname: ${guide.name}\ndescription: ${quote(guide.description)}\n---\n\n${guide.markdown.trim()}\n"
    }
}
