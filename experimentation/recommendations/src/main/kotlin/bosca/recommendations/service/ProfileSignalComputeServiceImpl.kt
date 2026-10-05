package bosca.recommendations.service

import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.service.ProfileAttributeService
import bosca.recommendations.model.PersonalizationSignal
import bosca.recommendations.model.PersonalizationSignalDefinition
import bosca.recommendations.model.PersonalizationSignalSourceType
import bosca.serialization.JsonConverter.toAny
import bosca.serialization.JsonConverter.toJsonElement
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import com.dashjoin.jsonata.Jsonata
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Default [ProfileSignalComputeService]: for a profile attribute, runs each matching enabled
 * [PersonalizationSignalDefinition]'s JSONata against the attribute (its value + confidence/verified/
 * source/visibility/priority context), collects the keyed results into a `List<PersonalizationSignal>`, and
 * caches it on `ProfileAttribute.signals` (null when empty). A definition whose JSONata errors or returns
 * nothing is skipped — a bad expression never fails the attribute write.
 */
@ServiceImplementation
class ProfileSignalComputeServiceImpl(
    private val definitions: PersonalizationSignalService,
    private val attributes: ProfileAttributeService,
    private val json: Json,
) : ProfileSignalComputeService {

    override suspend fun computeForAttributes(attributeIds: List<UUID>) {
        val resolved = attributeIds.distinct().mapNotNull { attributes.getById(it) }
        resolved.groupBy { it.typeId }.forEach { (typeId, batch) ->
            val compiled = compile(definitions.getEnabledBySource(PersonalizationSignalSourceType.ATTRIBUTE, typeId))
            batch.forEach { persist(it, compiled) }
        }
    }

    override suspend fun computeForProfileType(profileId: UUID, typeId: String) {
        // Every matching attribute is of the same type, so they share one set of enabled definitions — fetch
        // and compile the JSONata once for the whole batch rather than per attribute.
        val compiled = compile(definitions.getEnabledBySource(PersonalizationSignalSourceType.ATTRIBUTE, typeId))
        attributes.getAttributesByProfile(profileId).filter { it.typeId == typeId }.forEach { persist(it, compiled) }
    }

    override suspend fun recomputeForSource(sourceType: PersonalizationSignalSourceType, sourceId: String) {
        // Only attribute-sourced signals are cached on attributes; segment signals resolve at read time.
        if (sourceType != PersonalizationSignalSourceType.ATTRIBUTE) return
        // A whole type's attributes share one definition set: fetch + compile it once for the backfill.
        val compiled = compile(definitions.getEnabledBySource(PersonalizationSignalSourceType.ATTRIBUTE, sourceId))
        attributes.getByTypeId(sourceId).forEach { persist(it, compiled) }
    }

    private suspend fun persist(attribute: ProfileAttribute, compiled: List<Pair<String, Jsonata>>) {
        val signals = evaluate(attribute, compiled)
        val encoded: JsonElement? = if (signals.isEmpty()) {
            null
        } else {
            json.encodeToJsonElement(ListSerializer(PersonalizationSignal.serializer()), signals)
        }
        attributes.setSignals(attribute.id, encoded)
    }

    /** Compiles each definition's JSONata once, keyed by its signal key, so a batch can reuse it per attribute. */
    private fun compile(defs: List<PersonalizationSignalDefinition>): List<Pair<String, Jsonata>> =
        defs.map { it.key to Jsonata.jsonata(it.expression) }

    /**
     * Runs each definition's JSONata against the attribute. A null / absent result excludes that signal; a
     * JSONata error is logged and the signal skipped (never fails the whole write). Pure — the write-time
     * side effects live in [persist].
     */
    fun compute(attribute: ProfileAttribute, defs: List<PersonalizationSignalDefinition>): List<PersonalizationSignal> =
        evaluate(attribute, compile(defs))

    /** Evaluates pre-compiled JSONata expressions (see [compile]) against the attribute into keyed signals. */
    private fun evaluate(attribute: ProfileAttribute, compiled: List<Pair<String, Jsonata>>): List<PersonalizationSignal> {
        if (compiled.isEmpty()) return emptyList()
        val input = attributeInput(attribute).toAny()
        return compiled.mapNotNull { (key, expression) ->
            val result = try {
                expression.evaluate(input)
            } catch (e: Exception) {
                log.warn("Personalization signal '{}' JSONata failed for attribute {}: {}", key, attribute.id, e.message)
                return@mapNotNull null
            } ?: return@mapNotNull null
            PersonalizationSignal(key, result.toJsonElement())
        }
    }

    /** The JSON the JSONata runs against: the attribute's value plus its context, so an expression can gate on confidence/verified/… */
    private fun attributeInput(attribute: ProfileAttribute): JsonElement = buildJsonObject {
        put("attributes", attribute.attributes ?: JsonNull)
        put("confidence", attribute.confidence)
        put("verified", attribute.verified)
        put("source", attribute.source)
        put("visibility", attribute.visibility.name)
        put("priority", attribute.priority)
        put("typeId", attribute.typeId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ProfileSignalComputeServiceImpl::class.java)
    }
}
