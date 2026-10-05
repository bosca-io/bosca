package bosca.pipelines.annotation

/**
 * The server-loaded list a [SettingControl.REFERENCE] setting picks from. The editor already loads
 * each of these once for the pipeline page, so the generic renderer just binds the picker to the list
 * named here — no per-node-kind wiring.
 *
 * [NONE] is the annotation default sentinel (an annotation enum arg cannot be null); the KSP generator
 * maps it to `null` in the descriptor, exactly as `@InputSlot`'s `type = Unit::class` sentinel maps to
 * "any". [NONE] is therefore never serialized over GraphQL.
 */
enum class ReferenceSource {
    NONE, JOB, SCRIPT, PIPELINE, EVENT, ATTRIBUTE_TYPE, SEARCH_INDEX, SECRET,

    /** Audience segments available to domain nodes that fan work out over segment membership. */
    SEGMENT,

    /** All git-ci pipelines (a global list); the picker stores the chosen pipeline's UUID. */
    GIT_PIPELINE,

    /**
     * The declared artifacts of the git-ci pipeline named by a **sibling** `gitPipelineId` setting on the
     * same node. Unlike the other sources this is **node-dependent**, so the editor resolves it per-node
     * from the chosen pipeline (via `git.declaredArtifacts(pipelineId)`) rather than from one global list.
     * The picker stores the chosen artifact's coordinate.
     */
    GIT_ARTIFACT,

    /**
     * The object types that flow between nodes — catalogued types (from serial descriptors) plus reusable
     * named shapes. Lets a node (e.g. JSONata) declare its output as a specific custom type. The picker
     * stores the chosen type's identifier (a serial name, or `shape:<name>` for a named shape).
     */
    TYPE,

    /**
     * The global environment type catalog (development / staging / production / …) — for a relay node
     * (e.g. Get Environment) that resolves the concrete environment of that type within a run's own
     * program. The picker stores the chosen type's name.
     */
    ENVIRONMENT_TYPE,

    /**
     * The message projects the BML Message Server hosts (a global list, via
     * `communications.bmlMessageHostedProjects`). The picker stores the chosen project's key.
     */
    MESSAGE_PROJECT,

    /**
     * The templates of the message project named by a **sibling** `project` setting on the same node.
     * Like [GIT_ARTIFACT] this is **node-dependent**: the editor resolves it per-node from the chosen
     * project's hosted-template listing (which also carries each template's payload contract). The
     * picker stores the bare template key.
     */
    MESSAGE_TEMPLATE,

    /**
     * The notification type catalog (`communications.notificationTypes`) — the keys the preference
     * gate and unsubscribe scoping evaluate. The picker stores the chosen type's key.
     */
    NOTIFICATION_TYPE,
}
