package bosca.graphql

/**
 * Contexts supplied alongside the keys in one request-scoped DataLoader batch. Entries are positionally aligned
 * with the batch keys and normally contain [BatchContext] values generated from each field invocation.
 */
class BatchLoaderEnvironment(val keyContextsList: List<Any?>)
