package bosca.scripting.engine

interface Engine {

    suspend fun validate(script: String)

    fun invalidate(key: String, version: Int)

    fun invalidateAll()

    suspend fun <T> compile(key: String, version: Int, source: String): BoscaCompiledScript<T>
}