package bosca.bml.gradle

import org.gradle.deployment.internal.Deployment
import org.gradle.deployment.internal.DeploymentHandle
import org.gradle.process.internal.ExecHandle
import org.gradle.process.internal.ExecHandleState
import org.gradle.process.internal.JavaExecHandleBuilder
import javax.inject.Inject

/** Gradle deployment handle that owns the forked BML server JVM. */
open class BmlApplicationHandle @Inject constructor(
    private val builder: JavaExecHandleBuilder,
) : DeploymentHandle {
    private var process: ExecHandle? = null

    @Synchronized
    override fun isRunning(): Boolean = process?.state == ExecHandleState.STARTED

    @Synchronized
    override fun start(deployment: Deployment) {
        val status = deployment.status()
        if (status.failure != null || isRunning()) return
        process = builder.build().start()
    }

    @Synchronized
    override fun stop() {
        process?.abort()
        process = null
    }
}
