@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.hubspot.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.pipelines.node.InputNode
import bosca.pipelines.service.PipelineService
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Seeds the HubSpot integration's pipelines — the flows that replace the legacy HubSpot sync jobs:
 *
 *  - **Member add/remove** (event-triggered): an organization membership event already dispatches to
 *    any pipeline whose `acceptedInputType` is the event's FQDN and `triggered = true`, so these
 *    provide the association work on the runner — replacing `AddMemberToHubSpotJob` /
 *    `RemoveMemberFromHubSpotJob`.
 *  - **Sync contact / sync company** (manually invoked): JSON-input pipelines (a profile id), run on
 *    demand by `Mutation.profile.thirdparty.sync` / the bulk `SyncAll` job via
 *    [PipelineService.run] — replacing `AddToHubSpotJob` + its list/subscription fan-out jobs. A
 *    person syncs through the contact pipeline, any other profile through the company pipeline.
 *
 * Idempotent: each pipeline is created only when no pipeline already carries its
 * [bosca.pipelines.model.Pipeline.key], so a hand-edited or already-installed pipeline is left alone.
 * Built as graph JSON because the engine `Get Id` node (member graphs) and the discriminated node
 * shapes live in the pipelines engine, which this module can't reference directly. Every node carries
 * an explicit `position` so the seeded graph lays out left-to-right in the editor (the editor does no
 * auto-layout — an unpositioned node renders at the origin, stacked on every other).
 */
class HubSpotPipelinesInstaller(
    private val pipelines: PipelineService,
) : PackageInstaller {

    override val version: String = "1.1.1"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        seedMember(MEMBER_ADDED_KEY, "HubSpot: Member Added", ORG_MEMBER_ADDED, "hubspotAssociateMember")
        seedMember(MEMBER_REMOVED_KEY, "HubSpot: Member Removed", ORG_MEMBER_REMOVED, "hubspotRemoveMember")
        create(SYNC_CONTACT_KEY, "HubSpot: Sync Contact", InputNode.JSON_TYPE, triggered = false, graph = syncContactGraph())
        create(SYNC_COMPANY_KEY, "HubSpot: Sync Company", InputNode.JSON_TYPE, triggered = false, graph = syncCompanyGraph())
    }

    /**
     * Create the member pipeline only when no pipeline already holds [key] (idempotent re-install):
     * `event → Get Id(id)=organization + Get Id(memberId)=member → <memberNodeType>`.
     */
    private suspend fun seedMember(key: String, name: String, eventType: String, memberNodeType: String) {
        val graph = JsonObject(
            mapOf(
                "nodes" to JsonArray(
                    listOf(
                        node("input", "in", 60, 200, "acceptedType" to eventType),
                        node("getId", "organization", 300, 100, "field" to "id"),
                        node("getId", "member", 300, 300, "field" to "memberId"),
                        node(memberNodeType, "associate", 560, 200),
                    )
                ),
                "edges" to JsonArray(
                    listOf(
                        edge("e1", "in", "organization", targetPort = "in"),
                        edge("e2", "in", "member", targetPort = "in"),
                        edge("e3", "organization", "associate", targetPort = "organization"),
                        edge("e4", "member", "associate", targetPort = "member"),
                    )
                ),
            )
        )
        create(key, name, eventType, triggered = true, graph = graph)
    }

    /**
     * The contact sync graph: route on whether the profile is already in HubSpot, then add or update
     * accordingly, threading the profile id through each branch.
     *
     *  - **Unsynced** (top row): properties → Add (contacts) → stamp id → add to configured lists →
     *    subscribe → associate memberships.
     *  - **Synced** (bottom row): properties + the resolved id → Update (contacts); associate memberships.
     *
     * Associations run after the contact exists on both branches (the stamp records a brand-new id,
     * so [AssociateHubSpotMembershipsNode] resolves it either way), matching the legacy sync's end
     * state; list/subscription enrolment runs only for a newly-synced contact, as the job did.
     */
    private fun syncContactGraph(): JsonObject = JsonObject(
        mapOf(
            "nodes" to JsonArray(
                listOf(
                    node("input", "in", 60, 260, "acceptedType" to InputNode.JSON_TYPE),
                    node("hubspotSyncRoute", "route", 260, 260),
                    node("hubspotProperties", "propsNew", 480, 80),
                    node("hubspotAdd", "add", 700, 80),
                    node("hubspotAddProfileAttribute", "stamp", 920, 80),
                    node("hubspotAddToConfiguredLists", "lists", 1140, 80),
                    node("hubspotSubscribeToConfigured", "subscribe", 1360, 80),
                    node("hubspotAssociateMemberships", "associate", 1580, 260),
                    node("hubspotProperties", "propsExisting", 480, 440),
                    node("hubspotGetId", "getId", 480, 580),
                    node("hubspotUpdate", "update", 760, 480),
                )
            ),
            "edges" to JsonArray(
                listOf(
                    edge("e1", "in", "route", targetPort = "profile"),
                    // Unsynced → add path.
                    edge("e2", "route", "propsNew", sourcePort = "unsynced", targetPort = "in"),
                    edge("e3", "propsNew", "add", sourcePort = "out", targetPort = "properties"),
                    edge("e4", "route", "stamp", sourcePort = "unsynced", targetPort = "profile"),
                    edge("e5", "add", "stamp", sourcePort = "out", targetPort = "id"),
                    edge("e6", "stamp", "lists", sourcePort = "profile", targetPort = "profile"),
                    edge("e7", "lists", "subscribe", sourcePort = "profile", targetPort = "profile"),
                    edge("e8", "subscribe", "associate", sourcePort = "profile", targetPort = "profile"),
                    // Synced → update path.
                    edge("e9", "route", "propsExisting", sourcePort = "synced", targetPort = "in"),
                    edge("e10", "route", "getId", sourcePort = "synced", targetPort = "profile"),
                    edge("e11", "propsExisting", "update", sourcePort = "out", targetPort = "properties"),
                    edge("e12", "getId", "update", sourcePort = "out", targetPort = "hubspotId"),
                    edge("e13", "route", "associate", sourcePort = "synced", targetPort = "profile"),
                )
            ),
        )
    )

    /**
     * The company sync graph: the same add-vs-update route, against the HubSpot `companies` object,
     * with no list/subscription/association steps (companies don't take them) — matching the legacy
     * sync's non-generic branch.
     */
    private fun syncCompanyGraph(): JsonObject = JsonObject(
        mapOf(
            "nodes" to JsonArray(
                listOf(
                    node("input", "in", 60, 220, "acceptedType" to InputNode.JSON_TYPE),
                    node("hubspotSyncRoute", "route", 260, 220),
                    node("hubspotProperties", "propsNew", 480, 80),
                    node("hubspotAdd", "add", 700, 80, "objectType" to "companies"),
                    node("hubspotAddProfileAttribute", "stamp", 920, 80),
                    node("hubspotProperties", "propsExisting", 480, 360),
                    node("hubspotGetId", "getId", 480, 500),
                    node("hubspotUpdate", "update", 760, 420, "objectType" to "companies"),
                )
            ),
            "edges" to JsonArray(
                listOf(
                    edge("e1", "in", "route", targetPort = "profile"),
                    // Unsynced → add path.
                    edge("e2", "route", "propsNew", sourcePort = "unsynced", targetPort = "in"),
                    edge("e3", "propsNew", "add", sourcePort = "out", targetPort = "properties"),
                    edge("e4", "route", "stamp", sourcePort = "unsynced", targetPort = "profile"),
                    edge("e5", "add", "stamp", sourcePort = "out", targetPort = "id"),
                    // Synced → update path.
                    edge("e6", "route", "propsExisting", sourcePort = "synced", targetPort = "in"),
                    edge("e7", "route", "getId", sourcePort = "synced", targetPort = "profile"),
                    edge("e8", "propsExisting", "update", sourcePort = "out", targetPort = "properties"),
                    edge("e9", "getId", "update", sourcePort = "out", targetPort = "hubspotId"),
                )
            ),
        )
    )

    /** Save [key] only when no pipeline already holds it (idempotent re-install). */
    private suspend fun create(key: String, name: String, acceptedInputType: String, triggered: Boolean, graph: JsonObject) {
        if (pipelines.getByKey(key) != null) return
        pipelines.save(
            id = UUID.NIL,
            name = name,
            description = "Installed by the HubSpot integration.",
            acceptedInputType = acceptedInputType,
            triggered = triggered,
            version = 0,
            graph = graph,
            key = key,
        )
    }

    /**
     * One graph node as `{ "type": <serialName>, "id": <id>, "position": {x,y}, <fields…> }` ("type" is
     * the discriminator). [x]/[y] are the editor canvas position — required because the editor does no
     * auto-layout.
     */
    private fun node(type: String, id: String, x: Int, y: Int, vararg fields: Pair<String, String>): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive(type))
            put("id", JsonPrimitive(id))
            put("position", JsonObject(mapOf("x" to JsonPrimitive(x), "y" to JsonPrimitive(y))))
            fields.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        },
    )

    private fun edge(id: String, source: String, target: String, sourcePort: String? = null, targetPort: String? = null): JsonObject =
        JsonObject(
            buildMap {
                put("id", JsonPrimitive(id))
                put("source", JsonPrimitive(source))
                put("target", JsonPrimitive(target))
                sourcePort?.let { put("sourcePort", JsonPrimitive(it)) }
                targetPort?.let { put("targetPort", JsonPrimitive(it)) }
            },
        )

    companion object {
        const val MEMBER_ADDED_KEY = "hubspot-member-added"
        const val MEMBER_REMOVED_KEY = "hubspot-member-removed"
        const val SYNC_CONTACT_KEY = "hubspot-sync-contact"
        const val SYNC_COMPANY_KEY = "hubspot-sync-company"

        const val ORG_MEMBER_ADDED = "bosca.profile.organization.events.OrganizationMemberAdded"
        const val ORG_MEMBER_REMOVED = "bosca.profile.organization.events.OrganizationMemberRemoved"
    }
}
