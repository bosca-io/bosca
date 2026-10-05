package bosca.di

import kotlin.reflect.KClass

actual fun fullClassName(clazz: KClass<*>): String {
    return clazz.qualifiedName ?: clazz.simpleName ?: ""
}