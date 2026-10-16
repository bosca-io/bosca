package bosca.backup

/** The observed destination state of a declared restore object. */
enum class ObjectBackupRestoreObjectStatus {
    /** No object exists at the original destination key. */
    MISSING,

    /** Destination bytes match the declared length and SHA-256 digest. */
    MATCHING,

    /** Bytes differ and an earlier started checkpoint owns this restore output. */
    REPAIRABLE,

    /** Bytes differ without a started checkpoint authorizing repair. */
    CONFLICTING,
}
