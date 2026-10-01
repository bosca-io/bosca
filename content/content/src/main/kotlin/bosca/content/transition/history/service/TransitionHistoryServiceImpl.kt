package bosca.content.transition.history.service

import bosca.content.transition.history.model.CollectionTransitionHistory
import bosca.content.transition.history.model.MetadataTransitionHistory
import bosca.content.transition.history.repository.CollectionTransitionHistoryRepository
import bosca.content.transition.history.repository.MetadataTransitionHistoryRepository
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class TransitionHistoryServiceImpl(
    private val metadataRepository: MetadataTransitionHistoryRepository,
    private val collectionRepository: CollectionTransitionHistoryRepository
) : TransitionHistoryService {

    override suspend fun add(history: MetadataTransitionHistory) = metadataRepository.add(history)

    override suspend fun add(history: CollectionTransitionHistory) = collectionRepository.add(history)
}