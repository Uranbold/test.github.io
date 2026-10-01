package mn.navmn.app.ui

import android.Manifest
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import mn.navmn.app.location.LocationSource
import mn.navmn.app.permission.PermissionStatus
import javax.inject.Inject

/**
 * The one Activity (AppCompatActivity for per-app language, ADR-0009 §8). It renders the ViewModel's state, reads the
 * platform permission state and launches the OS dialogs; it holds no guidance state (AC 64).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels()
    @Inject lateinit var location: LocationSource

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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
        setContent {
            NavRoot(
                vm,
                PlatformActions(
                    openAppSettings = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    },
                    openLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    moveToBack = { moveTaskToBack(true) },
                ),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume() // AC 11–12: continue the pending action after the user returns from settings
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
