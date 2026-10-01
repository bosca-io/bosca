package bosca.core.errors

import platform.Foundation.NSError

fun NSError.asException(): Exception = Exception(localizedDescription)