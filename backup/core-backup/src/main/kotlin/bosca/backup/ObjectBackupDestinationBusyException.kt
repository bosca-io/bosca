package bosca.backup

/** Another capture, backup or restore execution owns this destination in the shared progress database. */
class ObjectBackupDestinationBusyException(val storageKey: String) : IllegalStateException(
    "Object storage has an active capture, backup or restore writer: $storageKey",
)
