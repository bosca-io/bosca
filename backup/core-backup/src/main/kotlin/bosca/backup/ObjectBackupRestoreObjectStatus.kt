package bosca.backup

/** The observed destination state of a declared restore object. */
enum class ObjectBackupRestoreObjectStatus {
    /** No object exists at the original destination key. */
    MISSING,

    /** Destination bytes match the declared length and SHA-256 digest. */
    MATCHING,

    /** Destination bytes differ; explicit recovery replaces this selected file. */
    DIFFERENT,
}
