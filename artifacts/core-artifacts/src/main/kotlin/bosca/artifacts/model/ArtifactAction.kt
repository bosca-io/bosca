package bosca.artifacts.model

/**
 * Actions that can be performed on artifacts, used by the permission evaluator
 * to check whether an API token's scopes grant the requested operation.
 */
enum class ArtifactAction(val value: String) {
    /** Download or read artifacts (docker pull, maven resolve, npm install). */
    PULL("pull"),
    /** Upload or publish artifacts (docker push, maven deploy, npm publish). */
    PUSH("push"),
    /** Delete artifacts, manage tags, manage namespace access. */
    ADMIN("admin");
}
