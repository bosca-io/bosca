package bosca.ai.kit.harness.runtime

/** Fixed metric dimensions; conversation and configuration identities belong only in event fields. */
enum class KitRuntimeOperation {
    RECONCILE, STARTUP, RESTORE, TURN, APPROVAL, ARCHIVE, CLEANUP,
    CREDENTIAL_ISSUE, CREDENTIAL_REVOKE, ADMISSION, MCP, CONTEXT, RECOVERY, DELETE, CHECKPOINT,
}
