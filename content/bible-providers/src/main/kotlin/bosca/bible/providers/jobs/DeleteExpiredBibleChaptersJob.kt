package bosca.bible.providers.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/** Maintenance request for expired provider chapter responses and their private tokens. */
@Serializable
class DeleteExpiredBibleChaptersJob : IJobDefinition
