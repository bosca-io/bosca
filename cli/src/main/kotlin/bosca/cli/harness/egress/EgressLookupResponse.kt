package bosca.cli.harness.egress

import kotlinx.serialization.Serializable

/** The body of `GET /api/v1/kit/egress`: the destinations approved for one runtime session. */
@Serializable
internal data class EgressLookupResponse(val destinations: List<String> = emptyList())
