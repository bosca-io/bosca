package bosca.storage.service

/**
 * Thrown by [ObjectStorageService] reads when nothing is stored at [path].
 *
 * Every backend reports a missing object with this type, so callers can tell "not there yet"
 * apart from a storage failure. It extends [NoSuchElementException] so existing handlers of that
 * type (such as the routes' 404 mapping) keep working. The message leaves out [path], since
 * handlers may pass it on to clients.
 */
class ObjectNotFoundException(val path: ObjectPath, cause: Throwable? = null) :
    NoSuchElementException("No object is stored at the requested path", cause)
