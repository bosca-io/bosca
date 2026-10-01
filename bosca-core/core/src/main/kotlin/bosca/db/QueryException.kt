package bosca.db

class QueryException(exception: Throwable) : RuntimeException(exception.message, exception)
