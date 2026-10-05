package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.ConfigurationInput
import bosca.graphql.gen.DeleteConfiguration
import bosca.graphql.gen.GetConfigurationValue
import bosca.graphql.gen.GetConfigurationValueData
import bosca.graphql.gen.SetConfiguration
import bosca.cli.util.decode
import java.util.concurrent.ConcurrentHashMap

class Configurations(network: NetworkClient) : Api(network) {

    private val configurations = ConcurrentHashMap<String, GetConfigurationValueData.Configurations.Configuration>()
    private val configurationIdKeys = ConcurrentHashMap<String, String>()

    private fun clearIdFromCache(id: String) {
        synchronized(configurationIdKeys) {
            val removeId = configurationIdKeys.remove(id) ?: return
            configurations.remove(removeId)
        }
    }

    suspend fun getRaw(key: String): Any? {
        val result = network.boscaGraphql.execute(GetConfigurationValue, GetConfigurationValue.Variables(key))
        result.configurations.configuration?.let {
            synchronized(configurationIdKeys) {
                configurationIdKeys[it.id] = it.key
                configurations[it.key] = it
            }
        }
        return configurations[key]?.value
    }

    suspend inline fun <reified T> get(key: String): T? {
        val raw = getRaw(key)
        return raw.decode<T>()
    }

    suspend fun set(configuration: ConfigurationInput) {
        val result = network.boscaGraphql.execute(SetConfiguration, SetConfiguration.Variables(configuration))
        val id = result.configurations.setConfiguration?.id ?: return
        clearIdFromCache(id)
    }

    suspend fun delete(key: String) {
        val result = network.boscaGraphql.execute(DeleteConfiguration, DeleteConfiguration.Variables(key))
        clearIdFromCache(result.configurations.deleteConfiguration ?: return)
    }
}
