package bosca.git.service

import bosca.git.model.RepositoryContentType
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-module plug-in point for [RepositoryContentValidator] instances. Each domain
 * module's installer registers its validator at startup; `GitPreReceiveHook` looks one
 * up per push based on the repository's [RepositoryContentType].
 *
 * The registry is mutated only during application startup (in [bosca.server.BoscaApplicationModule.install])
 * and read on every push thereafter. Backed by a [ConcurrentHashMap] so any race between
 * late startup-time registration and an early-arriving push lands a consistent value.
 */
class RepositoryContentValidatorRegistry {

    private val validators = ConcurrentHashMap<RepositoryContentType, RepositoryContentValidator>()

    fun register(validator: RepositoryContentValidator) {
        validators[validator.contentType] = validator
    }

    fun get(contentType: RepositoryContentType): RepositoryContentValidator? = validators[contentType]

    fun all(): Collection<RepositoryContentValidator> = validators.values
}
