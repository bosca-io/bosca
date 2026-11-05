package bosca.workops.service

import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.workops.model.task.Task

/** Shared task authorization through the owning task service and current group permissions. */
class TaskPermissionEvaluator(
    override val service: TaskService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<Task, UUID>()
