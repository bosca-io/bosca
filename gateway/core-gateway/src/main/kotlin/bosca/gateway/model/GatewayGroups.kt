package bosca.gateway.model

/**
 * Named security groups that grant cross-cutting privileges over the
 * gateway subsystem. These are referenced by string from controllers,
 * group-evaluator checks, and provisioning scripts — defining them
 * here keeps the names consistent.
 */
object GatewayGroups {
    /**
     * Membership grants the ability to create new [Gateway] entries
     * AND to repoint the upstream URL of existing gateways. Both are
     * privilege-escalation entry points (an attacker who can register
     * an upstream URL can pivot to internal services or exfiltrate
     * traffic), so they're gated behind this group rather than the
     * per-entity EDIT/MANAGE permissions.
     */
    const val ADMIN: String = "gateway-admin"
}
