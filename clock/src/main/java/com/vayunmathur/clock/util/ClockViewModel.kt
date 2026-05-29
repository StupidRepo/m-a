package com.vayunmathur.clock.util

import android.app.Application
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.ui.sendTimerNotification
import com.vayunmathur.library.util.DatabaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * ViewModel for the Clock app.
 *
 * Owns:
 *  - city → timezone map (loaded once from assets/cities.csv off the main thread)
 *  - a shared 100ms wall-clock tick (paused while no UI subscribes)
 *  - stopwatch run state, lap list, and derived counting time
 *  - inbound AlarmClock.ACTION_* intent dispatch (set alarm / set timer / show alarms)
 *
 * The shared [DatabaseViewModel] is injected so this VM can persist alarms / timers
 * created by external intents.
 */
class ClockViewModel(
    application: Application,
    private val databaseViewModel: DatabaseViewModel,
) : AndroidViewModel(application) {

    // --- City → timezone map ---------------------------------------------------

    private val _cities = MutableStateFlow<Map<String, String>?>(null)
    /** Lazily-loaded mapping of city name → IANA timezone ID. Null until loaded. */
    val cities: StateFlow<Map<String, String>?> = _cities.asStateFlow()

    // --- Shared wall-clock tick -----------------------------------------------

    private val tick = flow {
        while (true) {
            emit(Clock.System.now())
            delay(TICK_MS)
        }
    }

    /**
     * Current wall-clock time, updated every 100ms. Pauses when no screen
     * collects (via [SharingStarted.WhileSubscribed]).
     */
    val now: StateFlow<Instant> = tick.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        Clock.System.now(),
    )

    // --- Stopwatch state ------------------------------------------------------

    private val _stopwatchRunning = MutableStateFlow(false)
    val stopwatchRunning: StateFlow<Boolean> = _stopwatchRunning.asStateFlow()

    private val _stopwatchTotal = MutableStateFlow(Duration.ZERO)
    private val _stopwatchStart = MutableStateFlow(Clock.System.now())

    private val _lapTimes = MutableStateFlow<List<Duration>>(emptyList())
    val lapTimes: StateFlow<List<Duration>> = _lapTimes.asStateFlow()

    /**
     * Elapsed time the stopwatch should display. When running this includes the
     * live delta since the last start. Re-emits on every tick while a screen is
     * subscribed and pauses otherwise.
     */
    val stopwatchCountingTime: StateFlow<Duration> = combine(
        _stopwatchRunning,
        _stopwatchTotal,
        _stopwatchStart,
        now,
    ) { running, total, start, instant ->
        if (running) (instant - start) + total else total
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        Duration.ZERO,
    )

    fun toggleStopwatch() {
        if (_stopwatchRunning.value) {
            _stopwatchTotal.value = _stopwatchTotal.value + (Clock.System.now() - _stopwatchStart.value)
            _stopwatchRunning.value = false
        } else {
            _stopwatchStart.value = Clock.System.now()
            _stopwatchRunning.value = true
        }
    }

    fun resetStopwatch() {
        _stopwatchRunning.value = false
        _stopwatchTotal.value = Duration.ZERO
        _lapTimes.value = emptyList()
    }

    fun addLap() {
        _lapTimes.update { it + stopwatchCountingTime.value }
    }

    // --- Timer countdown helper ----------------------------------------------

    /** Remaining duration for [timer] at instant [now], clamped to >= 0. */
    fun timerRemaining(timer: Timer, now: Instant): Duration {
        val raw = if (timer.isRunning) {
            timer.remainingLength - (now - timer.remainingStartTime)
        } else {
            timer.remainingLength
        }
        return raw.coerceAtLeast(Duration.ZERO)
    }

    // --- Inbound AlarmClock intent dispatch ----------------------------------

    /**
     * Handle an inbound [AlarmClock] action intent. Returns the initial Route the
     * UI should navigate to, or null if the intent was either unrelated or
     * fully handled in the background (skipUi).
     */
    fun handleIncomingIntent(intent: Intent?): Route? {
        intent ?: return null
        return when (intent.action) {
            AlarmClock.ACTION_SET_ALARM -> {
                val hour = intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1).takeIf { it != -1 }
                val minutes = intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1).takeIf { it != -1 }
                val message = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE)
                val days = intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)
                val skipUi = intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)

                if (skipUi && hour != null && minutes != null) {
                    val time = LocalTime(hour, minutes)
                    var daysMask = 0
                    days?.forEach { day -> daysMask = daysMask or (1 shl (day - 1)) }
                    val alarm = Alarm(time, message ?: "", true, daysMask)
                    val ctx = getApplication<Application>()
                    viewModelScope.launch(Dispatchers.IO) {
                        val id = databaseViewModel.upsert(alarm)
                        AlarmScheduler.get().schedule(ctx, alarm.copy(id = id))
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
                    val timer = Timer(
                        true,
                        message ?: "",
                        Clock.System.now(),
                        length.seconds,
                        length.seconds,
                    )
                    val ctx = getApplication<Application>()
                    viewModelScope.launch(Dispatchers.IO) {
                        val id = databaseViewModel.upsert(timer)
                        sendTimerNotification(ctx, timer.copy(id = id), true)
                    }
                    null
                } else {
                    Route.NewTimerDialog(length, message)
                }
            }
            AlarmClock.ACTION_SHOW_ALARMS -> Route.Alarm
            else -> null
        }
    }

    // --- Init -----------------------------------------------------------------

    init {
        loadCities()
    }

    private fun loadCities() {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val map = try {
                ctx.assets.open("cities.csv").bufferedReader().readLines().drop(1)
                    .map { it.split(",") }
                    .filter {
                        val pop = it.getOrNull(14)?.toDoubleOrNull()
                        pop != null && pop > 100_000
                    }
                    .associate { it[1].replace("\"", "") to it[15] }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load cities.csv", e)
                emptyMap()
            }
            _cities.value = map
        }
    }

    companion object {
        private const val TAG = "ClockViewModel"
        private const val TICK_MS = 100L
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Factory for [ClockViewModel] that injects the shared [DatabaseViewModel]. */
class ClockViewModelFactory(
    private val application: Application,
    private val databaseViewModel: DatabaseViewModel,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ClockViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return ClockViewModel(application, databaseViewModel) as T
    }
}
