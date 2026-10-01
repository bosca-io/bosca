package bosca.core.experimentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

/** Composable state for reactive UI updates when flag values change. */
@Composable
fun FeatureFlagClient.rememberFlag(key: String): State<FlagEvaluation?> {
    val state = flags.collectAsState()
    return remember(key) { derivedStateOf { state.value[key] } }
}