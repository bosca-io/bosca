package bosca.core.platform.providers

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import bosca.di.provideBlockingNoSuspend

@Composable
inline fun <reified VM : ViewModel> provideViewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    key: String? = null,
): VM = viewModel(
    viewModelStoreOwner = viewModelStoreOwner,
    key = key,
) {
    provideBlockingNoSuspend()
}
