package com.vayunmathur.passwords

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.util.NavKey
import androidx.room.migration.Migration
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.biometric.unlockDatabaseWithBiometrics
import com.vayunmathur.passwords.data.PasswordDatabase
import com.vayunmathur.passwords.ui.MenuPage
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.ui.PasswordEditPage
import com.vayunmathur.passwords.ui.PasswordPage
import com.vayunmathur.passwords.ui.SettingsPage
import kotlinx.serialization.Serializable

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        unlockDatabaseWithBiometrics(
            activity = this,
            onSuccess = { passphrase ->
                val db = buildDatabase<PasswordDatabase>(encryptionPassword = passphrase)
                val viewModel = DatabaseViewModel(db,Password::class to db.passwordDao())
                setContent {
                    DynamicTheme {
                        Navigation(viewModel, passphrase)
                    }
                }
            },
            onFailure = {
                finish()
            }
        )
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Menu: Route

    @Serializable
    data class PasswordPage(val id: Long): Route

    @Serializable
    data class PasswordEditPage(val id: Long): Route

    @Serializable
    data object Settings: Route
}


@Composable
fun Navigation(viewModel: DatabaseViewModel, passphrase: String) {
    val backStack = rememberNavBackStack<Route>(Route.Menu)
    MainNavigation(backStack) {
        entry<Route.Menu>(metadata = ListPage()) {
            MenuPage(backStack, viewModel, passphrase)
        }
        entry<Route.PasswordPage>(metadata = ListDetailPage()) {
            PasswordPage(backStack, it.id, viewModel)
        }
        entry<Route.PasswordEditPage>(metadata = ListDetailPage()) {
            PasswordEditPage(backStack, it.id, viewModel)
        }
        entry<Route.Settings>(metadata = ListDetailPage()) {
            SettingsPage(backStack, viewModel)
        }
    }
}