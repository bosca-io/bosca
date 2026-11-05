package bosca.ai.kit.harness.acp

import java.io.IOException

/** The remote outcome requires reconciliation; a lost connection is not confirmed cancellation. */
class AcpConnectionException(cause: Throwable) : IOException("ACP connection ended before the operation completed", cause)
