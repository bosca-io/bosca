package bosca.workops.ui.collaboration

/** A malformed provider message; its content and credential are never copied into the error. */
internal class WorkOpsCollaborationProtocolException(cause: Exception) : RuntimeException("Invalid collaboration message", cause)
