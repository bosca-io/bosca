package bosca.di.annotation

import kotlin.reflect.KClass

/**
 * Describes a binding exposed by an [bosca.di.ObjectProvider].
 *
 * The annotated provider class is the source of the binding. The DI processor uses that source
 * only to collapse repeated discovery of the same binding and to distinguish it from a conflicting
 * binding declared by another provider.
 *
 * @property type raw type registered by the provider
 * @property name optional name used to resolve the binding
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
@Repeatable
annotation class Provides(val type: KClass<*>, val name: String = "")
