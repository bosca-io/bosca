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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import bosca.core.Res
import bosca.core.register_button
import bosca.core.register_identifier_label
import bosca.core.register_login_button
import bosca.core.register_name_label
import bosca.core.register_password_label
import bosca.core.register_title
import bosca.core.platform.providers.provideViewModel
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.intl.Locale
import bosca.core.security.type.ProfileVisibility
import bosca.core.security.model.ProfileAttributeInput
import bosca.core.security.model.ProfileInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun RegisterScreen(
    onRegisterSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit,
    viewModel: AuthViewModel = provideViewModel()
) {
    var name by remember { mutableStateOf("") }
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val events by viewModel.events.collectAsState(null)
    val isLoading by viewModel.isLoading.collectAsState()
    val focusManager = LocalFocusManager.current
    val languageTag = Locale.current.toLanguageTag()

    LaunchedEffect(events) {
        if (events is AuthViewModel.AuthEvent.SignupSuccess) {
            onRegisterSuccess()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        Text(stringResource(Res.string.register_title), style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(16.dp))
        TextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(Res.string.register_name_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) })
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = identifier,
            onValueChange = { identifier = it },
            label = { Text(stringResource(Res.string.register_identifier_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) })
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(Res.string.register_password_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                viewModel.signup(
                    identifier,
                    password,
                    ProfileInput(
                        name = name,
                        visibility = ProfileVisibility.USER,
                        attributes = listOf(
                            ProfileAttributeInput(
                                typeId = "bosca.profiles.email",
                                attributes = JsonObject(mapOf("email" to JsonPrimitive(identifier))),
                                priority = 1,
                                source = "signup",
                                confidence = 100,
                                visibility = ProfileVisibility.USER,
                            ),
                            ProfileAttributeInput(
                                typeId = "bosca.profiles.name",
                                attributes = JsonObject(mapOf("name" to JsonPrimitive(name))),
                                priority = 1,
                                source = "signup",
                                confidence = 100,
                                visibility = ProfileVisibility.USER,
                            )
                        )
                    ),
                    languageTag
                )
            })
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                viewModel.signup(
                    identifier,
                    password,
                    ProfileInput(
                        name = name,
                        visibility = ProfileVisibility.USER,
                        attributes = listOf(
                            ProfileAttributeInput(
                                typeId = "bosca.profiles.email",
                                attributes = JsonObject(mapOf("email" to JsonPrimitive(identifier))),
                                priority = 1,
                                source = "signup",
                                confidence = 100,
                                visibility = ProfileVisibility.USER,
                            ),
                            ProfileAttributeInput(
                                typeId = "bosca.profiles.name",
                                attributes = JsonObject(mapOf("name" to JsonPrimitive(name))),
                                priority = 1,
                                source = "signup",
                                confidence = 100,
                                visibility = ProfileVisibility.USER,
                            )
                        )
                    ),
                    languageTag
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && name.isNotBlank() && identifier.isNotBlank() && password.isNotBlank()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(Res.string.register_button))
            }
        }
        TextButton(onClick = onNavigateToLogin) {
            Text(stringResource(Res.string.register_login_button))
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
