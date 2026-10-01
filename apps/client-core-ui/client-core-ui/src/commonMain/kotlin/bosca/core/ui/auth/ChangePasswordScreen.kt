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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import bosca.core.platform.providers.provideViewModel

/** Changes the authenticated user's password (old password required). */
@Composable
fun ChangePasswordScreen(
    onBack: () -> Unit,
    viewModel: AccountViewModel = provideViewModel(),
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isLoading by viewModel.isLoading.collectAsState()
    val events by viewModel.events.collectAsState(null)

    LaunchedEffect(events) {
        when (val event = events) {
            is AccountViewModel.AccountEvent.PasswordChanged -> onBack()
            is AccountViewModel.AccountEvent.Error -> errorMessage = event.message
            else -> {}
        }
    }

    val mismatch = confirm.isNotEmpty() && newPassword != confirm
    val canSubmit = !isLoading && oldPassword.isNotBlank() && newPassword.length >= 8 && !mismatch

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Change password", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            PasswordField("Current password", oldPassword) { oldPassword = it; errorMessage = null }
            Spacer(Modifier.height(8.dp))
            PasswordField("New password", newPassword) { newPassword = it; errorMessage = null }
            Spacer(Modifier.height(8.dp))
            PasswordField("Confirm new password", confirm, isError = mismatch) { confirm = it; errorMessage = null }
            if (mismatch) {
                Spacer(Modifier.height(4.dp))
                Text("Passwords don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.changePassword(newPassword, oldPassword) },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSubmit,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Update password")
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

@Composable
private fun PasswordField(label: String, value: String, isError: Boolean = false, onValueChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}
