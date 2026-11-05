package bosca.cli.images

/** Immutable registry image identity and image-owned labels for deployment configuration. */
internal data class ImageInspection(val reference: String, val id: String, val labels: Map<String, String>, val os: String, val architecture: String)
