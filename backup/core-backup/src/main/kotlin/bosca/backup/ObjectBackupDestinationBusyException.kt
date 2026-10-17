package bosca.backup

/** Another run owns this write destination in the shared progress database. */
class ObjectBackupDestinationBusyException(val storageKey: String) : IllegalStateException(
    "Object storage has an active backup or restore writer: $storageKey",
)
