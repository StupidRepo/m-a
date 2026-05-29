package com.vayunmathur.findfamily.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.library.util.DatabaseViewModel
import dev.whyoleg.cryptography.algorithms.RSA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.geojson.Position
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * ViewModel for the FindFamily app.
 *
 * Owns:
 *  - selection state (selected user / waypoint, history vs present, historical position)
 *  - waypoint editing form state + persistence
 *  - raw per-user location-history flow (keyed on selected user)
 *  - location-permission flags (foreground + background)
 *  - cached feature-availability check (network provider + geocoder)
 *  - networking-backed writes invoked from dialogs (temporary-link creation,
 *    add/accept person)
 *  - one-time startup work previously triggered from composables (sync job,
 *    self-user registration, week-old location cleanup)
 */
class FindFamilyViewModel(
    application: Application,
    private val databaseViewModel: DatabaseViewModel,
) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication()

    // ------------------------------------------------------------------
    // Selection state
    // ------------------------------------------------------------------

    private val _selectedUserId = MutableStateFlow<Long?>(null)
    val selectedUserId: StateFlow<Long?> = _selectedUserId.asStateFlow()

    private val _selectedWaypointId = MutableStateFlow<Long?>(null)
    val selectedWaypointId: StateFlow<Long?> = _selectedWaypointId.asStateFlow()

    private val _isShowingPresent = MutableStateFlow(true)
    val isShowingPresent: StateFlow<Boolean> = _isShowingPresent.asStateFlow()

    private val _historicalPosition = MutableStateFlow<Position?>(null)
    val historicalPosition: StateFlow<Position?> = _historicalPosition.asStateFlow()

    fun setSelectedUserId(id: Long?) {
        _selectedUserId.value = id
    }

    fun setSelectedWaypointId(id: Long?) {
        _selectedWaypointId.value = id
    }

    fun setShowingPresent(value: Boolean) {
        _isShowingPresent.value = value
    }

    fun setHistoricalPosition(position: Position?) {
        _historicalPosition.value = position
    }

    fun selectUser(userId: Long) {
        _selectedUserId.value = userId
        _selectedWaypointId.value = null
        _isShowingPresent.value = true
    }

    fun clearSelection() {
        _selectedUserId.value = null
        _selectedWaypointId.value = null
    }

    /**
     * Apply the initial selection passed in via navigation. Called from the
     * MainPage entry so that opening the screen with a deep-linked user or
     * waypoint id selects it on arrival.
     */
    fun applyInitialSelection(initialUserId: Long?, initialWaypointId: Long?) {
        _selectedUserId.value = initialUserId
        _selectedWaypointId.value = initialWaypointId
    }

    // ------------------------------------------------------------------
    // Waypoint editing form
    // ------------------------------------------------------------------

    private val _waypointName = MutableStateFlow("")
    val waypointName: StateFlow<String> = _waypointName.asStateFlow()

    private val _waypointRange = MutableStateFlow("")
    val waypointRange: StateFlow<String> = _waypointRange.asStateFlow()

    private val _waypointCoord = MutableStateFlow(Coord(0.0, 0.0))
    val waypointCoord: StateFlow<Coord> = _waypointCoord.asStateFlow()

    fun setWaypointName(name: String) {
        _waypointName.value = name
    }

    fun setWaypointRange(range: String) {
        _waypointRange.value = range
    }

    fun setWaypointCoord(coord: Coord) {
        _waypointCoord.value = coord
    }

    /** Begin creating a brand-new waypoint with sensible defaults. */
    fun beginCreateWaypoint() {
        _selectedWaypointId.value = 0L
        _waypointName.value = ""
        _waypointRange.value = "100"
        _waypointCoord.value = Coord(0.0, 0.0)
    }

    /** Begin editing an existing waypoint, prefilling the form. */
    fun beginEditWaypoint(waypoint: Waypoint) {
        _selectedWaypointId.value = waypoint.id
        _waypointName.value = waypoint.name
        _waypointRange.value = waypoint.range.toString()
        _waypointCoord.value = waypoint.coord
    }

    /**
     * Persist the in-progress waypoint. Silently no-ops if the form is invalid
     * (matching the original FAB-click behaviour).
     */
    fun saveCurrentWaypoint() {
        val name = _waypointName.value
        val range = _waypointRange.value.toDoubleOrNull() ?: return
        if (name.isBlank()) return
        val id = _selectedWaypointId.value ?: return
        val coord = _waypointCoord.value
        viewModelScope.launch {
            val base = if (id == 0L) Waypoint.NEW_WAYPOINT else databaseViewModel.get<Waypoint>(id)
            databaseViewModel.upsert(base.copy(name = name, range = range, coord = coord))
            _selectedWaypointId.value = null
        }
    }

    // ------------------------------------------------------------------
    // Per-user location history (raw, filtered by selected user)
    // ------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    val locationHistory: StateFlow<List<LocationValue>> = _selectedUserId
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else databaseViewModel.data<LocationValue>("userid = $userId")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    private val _hasForeground = MutableStateFlow(
        ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    )
    val hasForeground: StateFlow<Boolean> = _hasForeground.asStateFlow()

    private val _hasBackground = MutableStateFlow(
        ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    )
    val hasBackground: StateFlow<Boolean> = _hasBackground.asStateFlow()

    /** Re-read both permission flags from the OS. Call on resume. */
    fun refreshPermissions() {
        _hasForeground.value = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        _hasBackground.value = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ------------------------------------------------------------------
    // Feature check (network provider + geocoder)
    // ------------------------------------------------------------------

    /**
     * True iff this device is missing one of the features FindFamily needs
     * (network location provider or system geocoder). Computed once.
     */
    val missingFeatures: Boolean by lazy {
        val locationManager =
            ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isNetworkEnabled =
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val isGeocoderPresent = Geocoder.isPresent()
        !isNetworkEnabled || !isGeocoderPresent
    }

    // ------------------------------------------------------------------
    // Networking-backed dialog actions
    // ------------------------------------------------------------------

    /**
     * Generate an RSA key pair and persist a [TemporaryLink] for sharing.
     * Calls [onDone] on the main thread once the upsert completes.
     */
    fun createTemporaryLink(name: String, expiry: Duration, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val keypair = Networking.generateKeyPair()
            val newLink = TemporaryLink(
                name,
                Base64.encode(
                    keypair.privateKey.encodeToByteArray(RSA.PrivateKey.Format.PEM)
                ),
                Base64.encode(
                    keypair.publicKey.encodeToByteArray(RSA.PublicKey.Format.PEM)
                ),
                Clock.System.now() + expiry,
            )
            databaseViewModel.upsert(newLink)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    /** Upsert a new/updated [User]; invokes [onDone] when persisted. */
    fun upsertUser(user: User, onDone: () -> Unit = {}) {
        databaseViewModel.upsertAsync(user) { onDone() }
    }

    // ------------------------------------------------------------------
    // One-time startup work
    // ------------------------------------------------------------------

    init {
        // Trim location history older than a week.
        viewModelScope.launch {
            val cutoff = Clock.System.now() - 7.days
            databaseViewModel.deleteIf<LocationValue>("timestamp < ${cutoff.epochSeconds}")
        }
        // Schedule the recurring sync work that drives location-tracking restarts.
        ensureSync(ctx)
        // Self-register: previously lived in MapView's LaunchedEffect. The 1s
        // delay matches the original, giving Networking.init() time to run in
        // the LocationTrackingService before we read Networking.userid.
        viewModelScope.launch {
            delay(1000)
            val users = databaseViewModel.getAll<User>()
            if (users.none { it.id == Networking.userid }) {
                withContext(Dispatchers.IO) {
                    databaseViewModel.upsert(
                        User(
                            ctx.getString(R.string.me_label),
                            null,
                            "Unnamed Location",
                            true,
                            RequestStatus.MUTUAL_CONNECTION,
                            Clock.System.now(),
                            null,
                            Networking.userid,
                        )
                    )
                }
            }
        }
    }
}

/** Factory for constructing [FindFamilyViewModel] with the shared [DatabaseViewModel]. */
class FindFamilyViewModelFactory(
    private val application: Application,
    private val databaseViewModel: DatabaseViewModel,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FindFamilyViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return FindFamilyViewModel(application, databaseViewModel) as T
    }
}
