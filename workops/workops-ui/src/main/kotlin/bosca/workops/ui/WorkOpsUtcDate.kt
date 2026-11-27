package bosca.workops.ui

import java.time.LocalDate
import java.time.ZoneOffset

/** Optional native date fields are stored at UTC midnight by the existing WorkOps APIs. */
internal fun workOpsUtcDate(value: String): String? = value.takeIf(String::isNotBlank)?.let {
    LocalDate.parse(it).atStartOfDay().atOffset(ZoneOffset.UTC).toString()
}
