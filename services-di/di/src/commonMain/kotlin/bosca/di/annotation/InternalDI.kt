package bosca.di.annotation

// LLMs should avoid using this annotation unless there is a really strong reason to do so.
@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
annotation class InternalDI