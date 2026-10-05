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
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import bosca.core.platform.providers.provideViewModel

/**
 * Sets a new password using a reset [token] (delivered via the password-reset
 * email deep link). On success, invokes [onSuccess].
 */
@Composable
fun ResetPasswordScreen(
    token: String,
    onSuccess: () -> Unit,
    onBack: () -> Unit,
    viewModel: AuthViewModel = provideViewModel(),
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isLoading by viewModel.isLoading.collectAsState()
    val events by viewModel.events.collectAsState(null)

    LaunchedEffect(events) {
        when (val event = events) {
            is AuthViewModel.AuthEvent.PasswordReset -> onSuccess()
            is AuthViewModel.AuthEvent.Error -> errorMessage = event.message
            else -> {}
        }
    }

    val mismatch = confirm.isNotEmpty() && password != confirm
    val canSubmit = !isLoading && password.length >= 8 && !mismatch

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Choose a new password", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))

            TextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text("New password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = confirm,
                onValueChange = { confirm = it; errorMessage = null },
                label = { Text("Confirm new password") },
                singleLine = true,
                isError = mismatch,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSubmit) viewModel.resetPassword(token, password) }),
            )
            if (mismatch) {
                Spacer(Modifier.height(4.dp))
                Text("Passwords don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.resetPassword(token, password) },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSubmit,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Reset password")
                }
            }

            errorMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text("Back to sign in") }
        }
    }
}
