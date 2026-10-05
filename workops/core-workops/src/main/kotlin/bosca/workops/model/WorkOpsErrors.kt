package bosca.workops.model

import bosca.serialization.UUID

/**
 * Common base for typed Work Ops failures. The Excellence Bar
 * non-negotiable in `specs/workops/requirements.md` requires every
 * mutation to surface a typed error rather than a free-form exception
 * string; this hierarchy is the load-bearing piece. Later phases may
 * extend the surface with additional error variants (workflow
 * condition failures, automation loop guards, etc.) — adding a new
 * subclass here makes it visible to every service / controller in
 * a single declarative move.
 */
sealed class WorkOpsException(message: String) : RuntimeException(message)

/** The targeted row was not found (or was soft-deleted on a path that requires an active row). */
class WorkOpsNotFoundException(val type: String, val handle: String) :
    WorkOpsException("$type not found: $handle")

/**
 * Optimistic-lock mismatch — the client's `expectedVersion` did not
 * match the persisted row. Per the Excellence Bar non-negotiable,
 * lost updates are unacceptable: the client reloads and retries.
 */
class OptimisticLockFailedException(val type: String, val id: UUID) :
    WorkOpsException("Optimistic lock failed: $type $id")

/**
 * The targeted parent (WorkOpsPortfolio / WorkOpsProgram / WorkOpsProject) is archived and
 * the operation requires a writable parent. R1 forbids cascading
 * un-archive so this is the boundary between read-only history and
 * live-write paths.
 */
class WorkOpsArchivedException(val type: String, val id: UUID) :
    WorkOpsException("$type is archived: $id")

/** A persisted invariant — formatting, ordering, hierarchy violation — was rejected. */
class WorkOpsValidationException(val field: String, val reason: String) :
    WorkOpsException("Invalid $field: $reason")

/** A unique-constraint conflict — duplicate key, duplicate name, etc. */
class WorkOpsConflictException(val type: String, val handle: String) :
    WorkOpsException("$type already exists: $handle")

/** A transition's condition predicate failed for the acting principal. */
class WorkflowConditionFailedException(val transitionName: String, val reason: String) :
    WorkOpsException("Transition '$transitionName' rejected by condition: $reason")

/** A transition's validator rejected the requested change. */
class WorkflowValidatorFailedException(val transitionName: String, val reason: String) :
    WorkOpsException("Transition '$transitionName' rejected by validator: $reason")

/** No such transition exists between the current and requested status. */
class WorkflowTransitionNotAvailableException(val taskId: UUID, val transitionId: UUID) :
    WorkOpsException("No matching transition $transitionId from task $taskId's current state")

/** A condition / validator / post-function variant requires a phase that hasn't shipped yet. */
class PendingPhaseImplementationException(val variant: String, val owningPhase: Int) :
    WorkOpsException("$variant is wired in Phase $owningPhase; this Work Ops build is earlier")

/** A `BLOCKS`-category link would close a cycle. */
class LinkCycleException(val sourceTaskId: UUID, val targetTaskId: UUID) :
    WorkOpsException("Link source=$sourceTaskId -> target=$targetTaskId would close a BLOCKS cycle")

/** The link graph reachable from one of the endpoints exceeds the depth-256 BFS bound. */
class LinkGraphTooDeepException(val rootTaskId: UUID) :
    WorkOpsException("Link graph reachable from $rootTaskId exceeds the 256-node depth bound")

/** A task's parent / epic does not satisfy the hierarchy rules (R7). */
class HierarchyViolationException(val taskId: UUID, val reason: String) :
    WorkOpsException("Hierarchy violation on $taskId: $reason")

/** A board already has a sprint in `ACTIVE` state (R8). */
class SprintAlreadyActiveException(val boardId: UUID) :
    WorkOpsException("Board $boardId already has an active sprint")

/**
 * Closing a sprint with unfinished tasks (R8) requires the caller to
 * supply a destination for each. The exception names the missing ids
 * so the client can re-issue with a complete map.
 */
class SprintCloseUnfinishedException(val sprintId: UUID, val unfinishedTaskIds: List<UUID>) :
    WorkOpsException(
        "Sprint $sprintId cannot close with ${unfinishedTaskIds.size} unfinished tasks lacking a destination",
    )

/** Cannot delete a `WorkOpsVersion` that tasks still reference (R9). */
class VersionInUseException(val versionId: UUID) :
    WorkOpsException("Version $versionId is still referenced by tasks; archive instead of delete")

class SpecCycleException(val specId: UUID, val parentSpecId: UUID) :
    WorkOpsException("Setting parent of spec $specId to $parentSpecId would create a cycle")
