package bosca.analytics

/** Compiled for every test target to keep the analytics IR transform covered by KMP target validation. */
@AutoInstrument(id = "kmp-probe", elementType = "validation")
internal fun compilerPluginKmpProbe(): String = "ok"
