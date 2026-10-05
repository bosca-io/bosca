package bosca.core.ui.providers

import bosca.core.security.BoscaAuth
import bosca.core.ui.auth.AccountViewModel
import bosca.core.ui.auth.AuthViewModel
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

@Providers
class ViewModelProviders {

    @Provider
    fun authViewModel(auth: BoscaAuth) = AuthViewModel(auth)

    @Provider
    fun accountViewModel(auth: BoscaAuth) = AccountViewModel(auth)
}
