package bosca.security.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/** Periodic maintenance request for expired security-token and login-revocation records. */
@Serializable
class DeleteExpiredSecurityTokensJob : IJobDefinition
