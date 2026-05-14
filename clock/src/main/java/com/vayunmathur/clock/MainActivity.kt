package com.vayunmathur.clock

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.ClockDatabase
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.ui.AlarmPage
import com.vayunmathur.clock.ui.ClockPage
import com.vayunmathur.clock.ui.StopwatchPage
import com.vayunmathur.clock.ui.TimerPage
import com.vayunmathur.clock.ui.dialogs.NewTimerDialog
import com.vayunmathur.clock.ui.dialogs.SelectTimeZonesDialog
import com.vayunmathur.clock.ui.sendTimerNotification
import com.vayunmathur.clock.util.AlarmScheduler
import com.vayunmathur.clock.util.createNotificationChannels
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.dialog.TimePickerDialogContent
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

var citiesToTimezones: Map<String, String>? by mutableStateOf(null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val alarmManager = getSystemService(AlarmManager::class.java)
        if (!alarmManager.canScheduleExactAlarms()) {
            // Redirect user to system settings to allow exact alarms
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
        createNotificationChannels(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!notificationManager.canUseFullScreenIntent()) {
                // Direct the user to the settings page to toggle "Allow full screen intents"
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = "package:${packageName}".toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        }
        val ds = DataStoreUtils.getInstance(this)
        val db = buildDatabase<ClockDatabase>(useDeviceProtectedStorage = true)
        val viewModel = DatabaseViewModel(db, Timer::class to db.timerDao(), Alarm::class to db.alarmDao())

        val initialRoute = when (intent.action) {
            AlarmClock.ACTION_SET_ALARM -> {
                val hour = intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1).takeIf { it != -1 }
                val minutes = intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1).takeIf { it != -1 }
                val message = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE)
                val days = intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)
                val skipUi = intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)

                if (skipUi && hour != null && minutes != null) {
                    val time = LocalTime(hour, minutes)
                    var daysMask = 0
                    days?.forEach { day ->
                        daysMask = daysMask or (1 shl (day - 1))
                    }
                    val alarm = Alarm(time, message ?: "", true, daysMask)
                    CoroutineScope(Dispatchers.IO).launch {
                        val id = viewModel.upsert(alarm)
                        AlarmScheduler.get().schedule(this@MainActivity, alarm.copy(id = id))
                    }
                    null
                } else {
                    Route.NewAlarmDialog(hour, minutes, message, days, skipUi)
                }
            }
            AlarmClock.ACTION_SET_TIMER -> {
                val length = intent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1).takeIf { it != -1 }
                val message = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE)
                val skipUi = intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)

                if (skipUi && length != null) {
                    val timer = Timer(true, message ?: "", Clock.System.now(), length.seconds, length.seconds)
                    CoroutineScope(Dispatchers.IO).launch {
                        val id = viewModel.upsert(timer)
                        sendTimerNotification(this@MainActivity, timer.copy(id = id), true)
                    }
                    null
                } else {
                    Route.NewTimerDialog(length, message)
                }
            }
            AlarmClock.ACTION_SHOW_ALARMS -> Route.Alarm
            else -> null
        }

        setContent {
            LaunchedEffect(Unit) {
                CoroutineScope(Dispatchers.IO).launch {
                    readTimezones(this@MainActivity)
                }
            }
            DynamicTheme {
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyArray()
                }
                var hasPermissions by remember {
                    mutableStateOf(
                        permissions.all {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                        }
                    )
                }
                if (!hasPermissions && permissions.isNotEmpty()) {
                    InitialPermissionsScreen(permissions) { hasPermissions = it }
                } else {
                    Navigation(ds, viewModel, initialRoute)
                }
            }
        }
    }
}

@Composable
fun InitialPermissionsScreen(permissions: Array<String>, setHasPermissions: (Boolean) -> Unit) {
    val permissionRequestor = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsResult ->
        setHasPermissions(permissionsResult.values.all { it })
    }
    LaunchedEffect(Unit) {
        permissionRequestor.launch(permissions)
    }
    Scaffold {
        Box(
            modifier = Modifier
                .padding(it)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Button(
                {
                    permissionRequestor.launch(permissions)
                }
            ) {
                Text(text = stringResource(R.string.grant_notifications_permission))
            }
        }
    }
}

fun readTimezones(context: Context) {
    citiesToTimezones =
        context.assets.open("cities.csv").bufferedReader().readLines().drop(1).map {
            it.split(",")
        }.filter {
            val pop = it[14].toDoubleOrNull()
            pop != null && pop > 100000
        }.associate {
            it[1].replace("\"", "") to it[15]
        }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Alarm: Route
    @Serializable
    data object Clock: Route
    @Serializable
    data object Timer: Route
    @Serializable
    data object Stopwatch: Route
    @Serializable
    data object SelectTimeZonesDialog: Route
    @Serializable
    data class NewTimerDialog(val lengthSeconds: Int? = null, val message: String? = null): Route
    @Serializable
    data class NewAlarmDialog(
        val hour: Int? = null,
        val minutes: Int? = null,
        val message: String? = null,
        val days: ArrayList<Int>? = null,
        val skipUi: Boolean = false
    ): Route
    @Serializable
    data class AlarmSetTimeDialog(val id: Long, val time: LocalTime): Route
}

@Composable
fun mainPages() = listOf(
    BottomBarItem(stringResource(R.string.label_alarm), Route.Alarm, R.drawable.baseline_access_alarm_24),
    BottomBarItem(stringResource(R.string.label_clock), Route.Clock, R.drawable.baseline_access_time_24),
    BottomBarItem(stringResource(R.string.label_timer), Route.Timer, R.drawable.baseline_hourglass_bottom_24),
    BottomBarItem(stringResource(R.string.label_stopwatch), Route.Stopwatch, R.drawable.outline_timer_24)
)

@Composable
fun Navigation(ds: DataStoreUtils, viewModel: DatabaseViewModel, initialRoute: Route?) {
    val backStack = rememberNavBackStack<Route>(listOfNotNull(Route.Alarm, initialRoute).distinct())
    MainNavigation(backStack) {
        entry<Route.Alarm> {
            AlarmPage(backStack, viewModel, initialRoute as? Route.NewAlarmDialog)
        }
        entry<Route.Clock> {
            ClockPage(backStack, ds)
        }
        entry<Route.Timer> {
            TimerPage(backStack, viewModel)
        }
        entry<Route.Stopwatch> {
            StopwatchPage(backStack)
        }
        entry<Route.SelectTimeZonesDialog>(metadata = DialogPage()) {
            SelectTimeZonesDialog(backStack, ds)
        }
        entry<Route.NewTimerDialog>(metadata = DialogPage()) { key ->
            NewTimerDialog(backStack, viewModel, key.lengthSeconds, key.message)
        }
        entry<Route.NewAlarmDialog>(metadata = DialogPage()) { key ->
            val initialTime = if (key.hour != null && key.minutes != null) {
                LocalTime(key.hour, key.minutes)
            } else {
                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
            }
            TimePickerDialogContent(backStack, "alarm_time", initialTime)
        }
        entry<Route.AlarmSetTimeDialog>(metadata = DialogPage()) {
            TimePickerDialogContent(backStack, "alarm_set_time_${it.id}", it.time)
        }
    }
}
