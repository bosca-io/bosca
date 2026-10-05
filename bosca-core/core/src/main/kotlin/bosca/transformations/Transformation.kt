package bosca.transformations

/**
 * Converts an item of type [T] into a result of type [R] within a given context [C].
 *
 * Used for content pipeline transformations (e.g. document conversion, image optimization)
 * where each step receives contextual information alongside the item to transform.
 *
 * @param C the context type carrying configuration or state for the transformation
 * @param T the input item type
 * @param R the output result type
 */
interface Transformation<C, T, R> {

    /**
     * Performs the transformation.
     *
     * @param context contextual data for this transformation step
     * @param item the input to transform
     * @return the transformed result
     */
    suspend fun transform(context: C, item: T): R
}