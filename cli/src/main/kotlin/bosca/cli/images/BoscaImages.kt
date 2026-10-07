package bosca.cli.images

/** A published Bosca image and its deployment settings. */
internal data class BoscaImage(val repository: String, val chart: String, val swarmKey: String? = null, val imagePath: String = "image")

internal const val PUBLIC_IMAGE_REGISTRY = "ghcr.io/bosca-io/bosca"

internal val boscaImages = listOf(
    BoscaImage("bosca-server", "bosca-server", "server"),
    BoscaImage("bosca-runner", "bosca-runner", "runner"),
    BoscaImage("analytics-collector", "bosca-collector", "collector"),
    BoscaImage("git-server", "bosca-git-server", "git"),
    BoscaImage("bosca-studio", "bosca-studio", "studio"),
    BoscaImage("profiles-web", "profiles-web", "profiles-web"),
    BoscaImage("notifications-web", "notifications-web", "notifications-web"),
    BoscaImage("bml-message-server", "bml-message-server", "bml"),
    BoscaImage("artifacts-server", "bosca-artifacts-server", "artifacts"),
    BoscaImage("imageprocessor", "imageprocessor", "imageprocessor"),
    BoscaImage("recommendation-trainer", "recommendation-trainer", "recommendation-trainer"),
    BoscaImage("recommendation-model-loader", "tf-serving", "recommendation-model-loader", "loader.image"),
    BoscaImage("kubernetes-controller", "bosca-kubernetes-controller"),
    BoscaImage("bosca-gateway", "bosca-gateway"),
    BoscaImage("bosca-io", "bosca-io"),
)
