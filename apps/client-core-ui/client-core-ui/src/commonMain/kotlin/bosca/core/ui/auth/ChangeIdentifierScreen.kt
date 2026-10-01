package bosca.core.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import bosca.core.platform.providers.provideViewModel

/** Changes the authenticated user's login identifier (current password required). */
@Composable
fun ChangeIdentifierScreen(
    onBack: () -> Unit,
    viewModel: AccountViewModel = provideViewModel(),
) {
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isLoading by viewModel.isLoading.collectAsState()
    val events by viewModel.events.collectAsState(null)

    LaunchedEffect(events) {
        when (val event = events) {
            is AccountViewModel.AccountEvent.IdentifierChanged -> onBack()
            is AccountViewModel.AccountEvent.Error -> errorMessage = event.message
            else -> {}
        }
    }

    val canSubmit = !isLoading && identifier.isNotBlank() && password.isNotBlank()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Change email", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            TextField(
                value = identifier,
                onValueChange = { identifier = it; errorMessage = null },
                label = { Text("New email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text("Current password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.changeIdentifier(identifier, password) },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSubmit,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Update email")
                }
            }
            errorMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text("Cancel") }
        }
    }
}
