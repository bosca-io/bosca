package bosca.cli.swarm.runtime

import kotlinx.serialization.json.*

internal fun runtimeProfile(image: String, network: String = "kit-runtime"): SwarmRuntimeProfile = SwarmRuntimeProfile(buildJsonObject {
    putJsonObject("Mode") { putJsonObject("Replicated") { put("Replicas", 1) } }
    putJsonObject("EndpointSpec") { put("Mode", "vip") }
    putJsonObject("TaskTemplate") {
        putJsonObject("ContainerSpec") {
            put("Image", image); put("User", "1000:1000"); put("ReadOnly", true); put("Init", false); put("Dir", "/state"); put("StopGracePeriod", 5_000_000_000L)
            putJsonArray("Command") { add("sh"); add("-c"); add("mkdir -m 700 /state/session && exec sleep 3600"); add("--") }
            putJsonArray("Args") { add("--root"); add("/state/session"); add("--private-pid-namespace") }
            putJsonArray("Env") { add("HOME=/state/session/home"); add("TMPDIR=/state/session/scratch"); add("CODEX_HOME=/state/session/harness"); add("CLAUDE_CONFIG_DIR=/state/session/harness"); add("CLAUDE_CODE_TMPDIR=/state/session/scratch") }
            putJsonArray("CapabilityDrop") { add("ALL") }
            putJsonObject("Privileges") { put("NoNewPrivileges", true) }
            putJsonArray("Mounts") {
                add(buildJsonObject {
                    put("Type", "tmpfs"); put("Target", "/state")
                    putJsonObject("TmpfsOptions") {
                        put("SizeBytes", 320L * 1024 * 1024); put("Mode", 1023)
                        putJsonArray("Options") { add(buildJsonArray { add("exec") }) }
                    }
                })
            }
        }
        putJsonObject("Resources") { putJsonObject("Limits") { put("NanoCPUs", 1_000_000_000); put("MemoryBytes", 1024L * 1024 * 1024); put("Pids", 256) } }
        putJsonObject("RestartPolicy") { put("Condition", "none") }
        putJsonArray("Networks") { add(buildJsonObject { put("Target", network) }) }
    }
})
