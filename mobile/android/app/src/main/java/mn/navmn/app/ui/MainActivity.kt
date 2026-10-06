package mn.navmn.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import mn.navmn.app.BuildConfig
import mn.navmn.app.R
import mn.navmn.app.background.BackgroundUi
import mn.navmn.app.background.battery.BatteryHint
import mn.navmn.app.background.battery.BatterySettings
import mn.navmn.app.background.restore.RestoreLauncher
import mn.navmn.app.location.LocationSource
import mn.navmn.app.log.CrashLog
import mn.navmn.app.lockscreen.LockScreenGate
import mn.navmn.app.map.MapSurface
import mn.navmn.app.permission.LocationAction
import mn.navmn.app.pack.PackNotifications
import mn.navmn.app.permission.PermissionStatus
import mn.navmn.app.theme.sun.SunTheme
import mn.navmn.app.ui.screens.CrashReportScreen
import mn.navmn.app.ui.theme.NavTheme
import javax.inject.Inject

/**
 * The one Activity (AppCompatActivity for per-app language, ADR-0009 §8). It renders the ViewModel's state, reads the
 * platform permission state and launches the OS dialogs; it holds no guidance state (AC 64).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels()
    /** NAV-022: the offline map (first-launch offer, «Тохиргоо» section, dialogs). */
    private val packVm: PackViewModel by viewModels()
    @Inject lateinit var location: LocationSource
    @Inject lateinit var mapSurface: MapSurface

    // NAV-012 (ADR-0013): restore on open, sunrise/sunset theme, battery hint, lock-screen gate.
    @Inject lateinit var restoreLauncher: RestoreLauncher
    @Inject lateinit var sunTheme: SunTheme
    @Inject lateinit var battery: BatteryHint
    private lateinit var lockGate: LockScreenGate
    private var askedRestoreLocation = false

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onLocationPermissionResult()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // AC 13: denied or not, guidance runs; only the visible notification is missing.
        vm.onNotificationPermissionHandled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // AC 60 / ADR-0009 §8: first launch opens in Mongolian whatever the device language (AppCompat: after onCreate).
        vm.settings.ensureLanguage()
        vm.bindPermissionState { permissionStatus() to location.servicesEnabled() }
        // NAV-012 AC 8–12, 34: over the lock screen only while guiding or on the arrival panel (also while stopped).
        lockGate = LockScreenGate(this)
        lifecycleScope.launch { vm.guidance.collect { lockGate.onGuidanceState(it) } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // NAV-012 AC 42: «Автомат» re-evaluated at once in the foreground, then every 30 s.
                launch { sunTheme.runWhileForeground() }
                launch { vm.myLocation.collect { f -> f?.let { sunTheme.onFix(it.latLon) } } }
                launch { vm.guidance.collect { g -> g?.puck?.takeIf { !it.stale }?.let { sunTheme.onFix(it.position) } } }
                // ADR-0009 Amendment 1 §9: map-screen location only while the Activity is visible (cancelled at onStop).
                launch { vm.collectMapLocation() }
                vm.ui.collect { ui ->
                    if (ui.requestLocationPermission) {
                        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                    if (ui.requestNotificationPermission) {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.onNotificationPermissionHandled()
                        }
                    }
                }
            }
        }
        openOfflineSectionIfAsked(intent)
        // B-NAV019-01 diagnostic (debug and demo builds only): the last crash report is shown before the map until closed.
        val crashLog = CrashLog.of(this)
        val lastCrash = if (BuildConfig.CRASH_DIAGNOSTICS) crashLog.read() else null
        setContent {
            var crashReport by remember { mutableStateOf(lastCrash) }
            val report = crashReport
            if (report != null) {
                NavTheme(night = false) {
                    CrashReportScreen(
                        report = report,
                        onCopy = { copyText(report) },
                        onShare = { shareText(report) },
                        onClose = {
                            crashLog.clear()
                            crashReport = null
                        },
                    )
                }
            } else NavRoot(
                vm,
                mapSurface,
                PlatformActions(
                    openAppSettings = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    },
                    openLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    moveToBack = { moveTaskToBack(true) },
                    requireUnlocked = { action -> lockGate.requireUnlocked(action) },
                    openBatterySettings = { BatterySettings.open(this) },
                    openUrl = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                ),
                background = BackgroundUi(sunTheme.night, battery),
                pack = packVm,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openOfflineSectionIfAsked(intent)
    }

    /** NAV-022 B4: a pack notification opens «Тохиргоо» scrolled to the pack section (guidance continues underneath). */
    private fun openOfflineSectionIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(PackNotifications.EXTRA_OPEN_OFFLINE, false) != true || !packVm.enabled) return
        intent.removeExtra(PackNotifications.EXTRA_OPEN_OFFLINE)
        packVm.requestSection()
        lockGate.requireUnlocked { vm.openSettings(true) }
    }

    override fun onStart() {
        super.onStart()
        // NAV-012 Layout rule 11 (ADR-0013 §5 forced view): over the lock screen only the guidance screen is drawn.
        if (lockGate.locked && vm.guiding) vm.openSettings(false)
    }

    override fun onStop() {
        super.onStop()
        // NAV-012 AC 10–11 (ADR-0013 Amendment 1): the arrival panel left while locked / screen off stops showing over
        // the lock screen for the rest of the session.
        lockGate.onStop()
    }

    override fun onResume() {
        super.onResume()
        vm.onResume() // AC 11–12: continue the pending action after the user returns from settings
        battery.refresh() // NAV-012 AC 27: the hint goes ≤ 2 s after returning with the exemption granted
        // NAV-012 AC 18, 21, 23, 24: restore an interrupted session (launcher, Recents or the AC 22 notification).
        val locationOk = permissionStatus() == PermissionStatus.PRECISE && location.servicesEnabled()
        lifecycleScope.launch {
            val outcome = restoreLauncher.check(locationOk)
            if (outcome == RestoreLauncher.Outcome.NEED_LOCATION && !askedRestoreLocation) {
                askedRestoreLocation = true
                vm.requireLocation(LocationAction.MY_LOCATION) // NAV-005 AC 8–12 flow; the record is kept (AC 23)
            }
        }
    }

    private fun copyText(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivity(Intent.createChooser(send, null)) }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun permissionStatus(): PermissionStatus = when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) -> PermissionStatus.PRECISE
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> PermissionStatus.APPROXIMATE
        !vm.hasAskedLocation -> PermissionStatus.NOT_ASKED
        shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> PermissionStatus.DENIED
        else -> PermissionStatus.DENIED_PERMANENT
    }
}
