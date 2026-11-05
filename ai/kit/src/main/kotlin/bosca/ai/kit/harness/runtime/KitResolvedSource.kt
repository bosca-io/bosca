package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitContextSource

/** Ephemeral, authorized source text; only its source identity/revision enters the durable manifest. */
internal data class KitResolvedSource(val source: KitContextSource, val title: String, val body: String)
