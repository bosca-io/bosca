package bosca.backup

/** Indicates that stored bytes do not match the declared backup payload length or digest. */
internal class BackupPayloadMismatchException(message: String) : IllegalStateException(message)
