package bosca.core.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import bosca.core.Res
import bosca.core.verify_button
import bosca.core.verify_code_label
import bosca.core.verify_default_identifier
import bosca.core.verify_logout_button
import bosca.core.verify_message
import bosca.core.verify_refresh_button
import bosca.core.verify_resend_button
import bosca.core.verify_title
import bosca.core.platform.providers.provideViewModel
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

@Composable
fun VerifyScreen(viewModel: AuthViewModel = provideViewModel()) {

    var token by remember { mutableStateOf("") }
    val events by viewModel.events.collectAsState(null)
    val isLoading by viewModel.isLoading.collectAsState()
    val identifier by viewModel.identifier.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        Text(stringResource(Res.string.verify_title), style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(Res.string.verify_message, identifier ?: stringResource(Res.string.verify_default_identifier)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        TextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(Res.string.verify_code_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.verify(token) })
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { viewModel.verify(token) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && token.isNotBlank()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(Res.string.verify_button))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = { identifier?.let { viewModel.resendVerification(it) } },
            enabled = !isLoading && identifier != null
        ) {
            Text(stringResource(Res.string.verify_resend_button))
        }
        TextButton(
            onClick = { viewModel.refreshVerificationStatus() },
            enabled = !isLoading
        ) {
            Text(stringResource(Res.string.verify_refresh_button))
        }
        TextButton(onClick = { viewModel.logout() }) {
            Text(stringResource(Res.string.verify_logout_button))
        }
        if (events is AuthViewModel.AuthEvent.Error) {
            Text(
                text = (events as AuthViewModel.AuthEvent.Error).message,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
}
