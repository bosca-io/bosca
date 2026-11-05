package bosca.cli.api

/** No request was submitted to Bosca, so there is no remote turn to cancel. */
internal class KitTurnCancelledBeforeSubmission : IllegalStateException("Kit request was cancelled before submission.")
