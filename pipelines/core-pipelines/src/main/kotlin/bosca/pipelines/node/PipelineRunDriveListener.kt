package bosca.pipelines.node

import bosca.sharedqueue.jobs.JobListener

/**
 * Marker for the **run job's drive hook** ("run = a job"). The run job carries this as a
 * `JobCallback(listener = PipelineRunDriveListener::class)`; when one of its immediate child jobs — a
 * backing job a suspendable node enqueued and parked the run on — reaches a terminal status, the
 * registered implementation's [JobListener.onChildStatusChanged] resumes the run from that node: it
 * drives the next segment and, if the run parks again, enqueues the next backing job as the next child.
 * The run job stays not-fully-complete while a child is outstanding, so it is the parent that completes
 * exactly when the run does.
 *
 * It lives in `core-pipelines` so the run-job wiring (any module that drives a durable run) can attach
 * it without depending on the pipelines implementation; the concrete listener is registered under this
 * type and `JobCallback` resolves it from the DI registry.
 */
interface PipelineRunDriveListener : JobListener
