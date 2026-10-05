package bosca.git.model

/**
 * A pipeline definition cannot produce a run for the requested trigger and parameters: trigger
 * inputs are invalid or missing, or no job survives condition and environment selection. Retrying
 * the same request yields the same rejection.
 */
class PipelinePlanRejectedException(message: String) : IllegalArgumentException(message)
