package bosca.graphql

class BatchContext<T>(
    val arguments: Map<String, Any?>,
    val context: T
)
