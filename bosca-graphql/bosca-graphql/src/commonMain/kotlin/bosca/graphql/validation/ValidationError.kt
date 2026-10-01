package bosca.graphql.validation

import bosca.graphql.language.SourceLocation

/** A single problem found while validating an executable document against a schema. */
data class ValidationError(val message: String, val location: SourceLocation?)
