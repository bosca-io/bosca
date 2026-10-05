package bosca.core.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import bosca.core.Res
import bosca.core.login_continue_button
import bosca.core.login_divider_text
import bosca.core.login_google_button
import bosca.core.login_identifier_label
import bosca.core.login_password_hide
import bosca.core.login_password_label
import bosca.core.login_password_show
import bosca.core.login_register_button
import bosca.core.login_title
import bosca.core.platform.providers.provideViewModel
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.intl.Locale
import bosca.core.security.ThirdPartyProvider

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit,
    appIcon: @Composable () -> Unit,
    onNavigateToForgotPassword: () -> Unit = {},
    onVerificationRequired: () -> Unit = {},
    viewModel: AuthViewModel = provideViewModel()
) {
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val events by viewModel.events.collectAsState(null)
    val isLoading by viewModel.isLoading.collectAsState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(events) {
        when (events) {
            is AuthViewModel.AuthEvent.LoginSuccess -> onLoginSuccess()
            // The account exists but isn't verified — send the user to the verification screen rather than
            // surfacing the gate error inline.
            is AuthViewModel.AuthEvent.VerificationRequired -> onVerificationRequired()
            else -> {}
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            appIcon()
            Spacer(Modifier.height(12.dp))
            Text(stringResource(Res.string.login_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))

            TextField(
                value = identifier,
                onValueChange = { identifier = it },
                label = { Text(stringResource(Res.string.login_identifier_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) })
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(Res.string.login_password_label)) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.login(identifier, password) }),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(if (passwordVisible) stringResource(Res.string.login_password_hide) else stringResource(Res.string.login_password_show))
                    }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { viewModel.login(identifier, password) },
                modifier = Modifier.fillMaxWidth(),
                enabled = isLoginEnabled(isLoading, identifier, password)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(Res.string.login_continue_button))
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.login_divider_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                HorizontalDivider(modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))

            val languageTag = Locale.current.toLanguageTag()
            val googleSignInLabel = stringResource(Res.string.login_google_button)
            OutlinedButton(
                onClick = {
                    viewModel.login(ThirdPartyProvider.GOOGLE, languageTag)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = googleSignInLabel }
            ) {
                Text(googleSignInLabel)
            }

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onNavigateToRegister) {
                Text(stringResource(Res.string.login_register_button))
            }
            TextButton(onClick = onNavigateToForgotPassword) {
                Text("Forgot password?")
            }

            if (events is AuthViewModel.AuthEvent.Error) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = (events as AuthViewModel.AuthEvent.Error).message,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

internal fun isLoginEnabled(isLoading: Boolean, identifier: String, password: String): Boolean =
    !isLoading && identifier.isNotBlank() && password.isNotBlank()
