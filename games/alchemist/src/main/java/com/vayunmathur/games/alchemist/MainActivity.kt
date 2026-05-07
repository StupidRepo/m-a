package com.vayunmathur.games.alchemist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import com.vayunmathur.games.alchemist.ui.HomeScreen
import com.vayunmathur.games.alchemist.ui.ItemDetailsScreen
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable
import com.vayunmathur.games.alchemist.data.Alchemist
import com.vayunmathur.library.util.AchievementsManager
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ds = DataStoreUtils.getInstance(this)
        Alchemist.init(this)
        setContent {
            DynamicTheme {
                Navigation(ds)
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Home: Route
    @Serializable
    data class ItemDetails(val item: Int): Route
    @Serializable
    data object GameCenter: Route
}

@Composable
fun Navigation(ds: DataStoreUtils) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    val achievementsManager = rememberAchievementsManager()
    val newAchievement by achievementsManager.newAchievement.collectAsState()

    LaunchedEffect(Unit) {
        achievementsManager.checkExistingAchievements()
    }

    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Home> {
                HomeScreen(backStack, ds, achievementsManager, onOpenGameCenter = { backStack.add(Route.GameCenter) })
            }
            entry<Route.ItemDetails> {
                ItemDetailsScreen(backStack, ds, it.item)
            }
            entry<Route.GameCenter> {
                com.vayunmathur.library.ui.GameCenterScreen(
                    backupAgent = com.vayunmathur.games.alchemist.util.AppBackupAgent(),
                    manager = achievementsManager,
                    onBack = { backStack.pop() }
                )
            }
        }

        newAchievement?.let {
            com.vayunmathur.library.ui.AchievementNotification(it) {
                achievementsManager.dismissNotification()
            }
        }
    }
}

@Composable
fun rememberAchievementsManager(): AchievementsManager {
    val context = LocalContext.current
    return remember {
        val json = context.assets.open("achievements.json").bufferedReader().use { it.readText() }
        com.vayunmathur.games.alchemist.util.AlchemistAchievementsManager(context, json)
    }
}