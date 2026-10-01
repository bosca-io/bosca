package bosca.git.model

/** The uploaded bytes do not match the declared LFS object digest or size. */
class LfsUploadValidationException : IllegalArgumentException("LFS object content does not match its oid and size")
