package bosca.cli.swarm

/** SSH hosts used by the deployment, with a manager that also runs applications visited once. */
internal val SwarmConfig.sshTargets: List<String>
    get() = (listOf(manager.ssh) + workers.map { it.ssh }).distinct()
