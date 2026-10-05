package bosca.cli.ci

import kotlin.uuid.Uuid

sealed class CiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class InsufficientDiskException(
    val availableMb: Long,
    val requiredMb: Long,
) : CiException("Insufficient disk space: ${availableMb}MB available, ${requiredMb}MB required")

class SecretDecryptionException(
    val repositoryId: Uuid,
    cause: Throwable,
) : CiException("Failed to decrypt pipeline secrets for repository $repositoryId", cause)

class StepTimeoutException(
    val stepName: String,
    val timeoutMinutes: Int,
) : CiException("Step '$stepName' timed out after $timeoutMinutes minutes")

class CloneFailedException(
    val repositoryId: String,
    val commitSha: String,
    val exitCode: Int,
) : CiException("Failed to clone repository $repositoryId at $commitSha (exit code $exitCode)")

class DockerUnavailableException : CiException("Docker daemon is not accessible. Ensure Docker is installed and running.")

class PipelineRunNotFoundException(
    val runId: Uuid,
) : CiException("Pipeline run $runId not found")

class ArtifactNotFoundException(
    val name: String,
) : CiException("Artifact '$name' not found locally or on server")

class VmProvisioningException(
    val provider: String,
    val reason: String,
    cause: Throwable? = null,
) : CiException("Failed to provision VM via $provider: $reason", cause)

class VmDeletionException(
    val vmId: String,
    val attempts: Int,
) : CiException("Failed to delete VM $vmId after $attempts attempts")
