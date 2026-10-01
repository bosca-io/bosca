package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.*
import java.time.ZonedDateTime
import kotlin.uuid.Uuid

class Workflows(network: NetworkClient) : Api(network) {

    suspend fun setMetadataWorkflowStateComplete(metadataId: Uuid, status: String) {
        network.boscaGraphql.execute(
            SetMetadataWorkflowStateComplete,
            SetMetadataWorkflowStateComplete.Variables(MetadataWorkflowCompleteState(metadataId, status)),
        )
    }

    suspend fun setCollectionWorkflowStateComplete(metadataId: Uuid, status: String) {
        network.boscaGraphql.execute(
            SetCollectionWorkflowStateComplete,
            SetCollectionWorkflowStateComplete.Variables(CollectionWorkflowCompleteState(metadataId, status)),
        )
    }

    suspend fun setMetadataWorkflowState(
        metadataId: Uuid,
        stateId: String,
        status: String,
        immediate: Boolean = false
    ) {
        network.boscaGraphql.execute(
            SetMetadataWorkflowState,
            SetMetadataWorkflowState.Variables(MetadataWorkflowState(immediate, metadataId, stateId, status)),
        )
    }

    suspend fun setCollectionWorkflowState(
        collectionId: Uuid,
        stateId: String,
        status: String,
        immediate: Boolean = false
    ) {
        network.boscaGraphql.execute(
            SetCollectionWorkflowState,
            SetCollectionWorkflowState.Variables(CollectionWorkflowState(collectionId, immediate, stateId, status)),
        )
    }

    suspend fun getTraits(): List<ITrait> =
        network.boscaGraphql.execute(GetTraits, Unit).traits.all

    suspend fun getTrait(id: String): ITrait? =
        network.boscaGraphql.execute(GetTrait, GetTrait.Variables(id)).traits.trait

    suspend fun addTrait(input: TraitInput): ITrait? =
        network.boscaGraphql.execute(AddTrait, AddTrait.Variables(input)).traits.add

    suspend fun editTrait(input: TraitInput): ITrait? =
        network.boscaGraphql.execute(EditTrait, EditTrait.Variables(input)).traits.edit

    suspend fun deleteTrait(id: String) {
        network.boscaGraphql.execute(DeleteTrait, DeleteTrait.Variables(id))
    }

    suspend fun getPrompts(): List<IPrompt> =
        network.boscaGraphql.execute(GetPrompts, Unit).prompts.all

    suspend fun getPrompt(id: Uuid): IPrompt? =
        network.boscaGraphql.execute(GetPrompt, GetPrompt.Variables(id)).prompts.prompt

    suspend fun addPrompt(input: PromptInput): IPrompt? =
        network.boscaGraphql.execute(AddPrompt, AddPrompt.Variables(input)).prompts.add

    suspend fun editPrompt(id: Uuid, input: PromptInput): IPrompt? =
        network.boscaGraphql.execute(EditPrompt, EditPrompt.Variables(id, input)).prompts.edit

    suspend fun deletePrompt(id: Uuid) {
        network.boscaGraphql.execute(DeletePrompt, DeletePrompt.Variables(id))
    }

    suspend fun getModels(): List<IModel> =
        network.boscaGraphql.execute(GetModels, Unit).models.all

    suspend fun getModel(id: Uuid): IModel? =
        network.boscaGraphql.execute(GetModel, GetModel.Variables(id)).models.model

    suspend fun addModel(model: ModelInput): IModel? =
        network.boscaGraphql.execute(AddModel, AddModel.Variables(model)).models.add

    suspend fun editModel(id: Uuid, model: ModelInput): IModel? =
        network.boscaGraphql.execute(EditModel, EditModel.Variables(id, model)).models.edit

    suspend fun deleteModel(id: Uuid) {
        network.boscaGraphql.execute(DeleteModel, DeleteModel.Variables(id))
    }

    suspend fun getTransitions(): List<ITransition> =
        network.boscaGraphql.execute(GetTransitions, Unit).transitions.all

    suspend fun getTransition(fromStateId: String, toStateId: String): ITransition? =
        network.boscaGraphql.execute(GetTransition, GetTransition.Variables(fromStateId, toStateId)).transitions.transition

    suspend fun addTransition(transition: TransitionInput): ITransition? =
        network.boscaGraphql.execute(AddTransition, AddTransition.Variables(transition)).transitions.add

    suspend fun editTransition(transition: TransitionInput): ITransition? =
        network.boscaGraphql.execute(EditTransition, EditTransition.Variables(transition)).transitions.edit

    suspend fun deleteTransition(fromStateId: String, toStateId: String) {
        network.boscaGraphql.execute(DeleteTransition, DeleteTransition.Variables(fromStateId, toStateId))
    }

    suspend fun beginMetadataTransition(
        id: Uuid,
        version: Int,
        state: String,
        status: String,
        stateValid: ZonedDateTime? = null,
        restart: Boolean = false
    ) {
        network.boscaGraphql.execute(
            BeginMetadataTransition,
            BeginMetadataTransition.Variables(
                id = id,
                version = version,
                state = state,
                stateValid = stateValid,
                status = status,
                restart = restart,
            ),
        )
    }

    suspend fun cancelMetadataTransition(id: Uuid, version: Int) {
        network.boscaGraphql.execute(CancelMetadataTransition, CancelMetadataTransition.Variables(id, version))
    }

    suspend fun beginCollectionTransition(id: Uuid, state: String, status: String, restart: Boolean = false) {
        network.boscaGraphql.execute(
            BeginCollectionTransition,
            BeginCollectionTransition.Variables(id, state, status, restart),
        )
    }

    suspend fun getStates(): List<IWorkflowState> =
        network.boscaGraphql.execute(GetStates, Unit).states.all

    suspend fun getState(id: String): IWorkflowState? =
        network.boscaGraphql.execute(GetState, GetState.Variables(id)).states.state

    suspend fun addState(state: WorkflowStateInput): IWorkflowState? =
        network.boscaGraphql.execute(AddState, AddState.Variables(state)).states.add

    suspend fun editState(state: WorkflowStateInput): IWorkflowState? =
        network.boscaGraphql.execute(EditState, EditState.Variables(state)).states.edit

    suspend fun deleteState(id: String) {
        network.boscaGraphql.execute(DeleteState, DeleteState.Variables(id))
    }

    suspend fun getStorageSystems(): List<IStorageSystem> =
        network.boscaGraphql.execute(GetStorageSystems, Unit).storageSystems.all

    suspend fun getStorageSystem(id: Uuid): IStorageSystem? =
        network.boscaGraphql.execute(GetStorageSystem, GetStorageSystem.Variables(id)).storageSystems.storageSystem

    suspend fun addStorageSystem(input: StorageSystemInput): IStorageSystem? =
        network.boscaGraphql.execute(AddStorageSystem, AddStorageSystem.Variables(input)).storageSystems.add

    suspend fun editStorageSystem(id: Uuid, input: StorageSystemInput): IStorageSystem? =
        network.boscaGraphql.execute(EditStorageSystem, EditStorageSystem.Variables(id, input)).storageSystems.edit

    suspend fun deleteStorageSystem(id: Uuid) {
        network.boscaGraphql.execute(DeleteStorageSystem, DeleteStorageSystem.Variables(id))
    }
}
