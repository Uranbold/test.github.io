package mn.navmn.app.android

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.ResourceStrings
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.service.GuidanceForegroundService
import mn.navmn.app.support.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Robolectric: merged manifest (AC 8, 15, 66, 67) and the shipped resources through the platform (AC 60, 61). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ManifestAndResourcesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun permissionsNeverIncludeBackgroundLocation() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val perms = info.requestedPermissions!!.toSet()
        assertFalse("AC 8", perms.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        assertTrue(perms.containsAll(listOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.FOREGROUND_SERVICE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.INTERNET,
        )))
    }

    @Test
    fun foregroundServiceIsTypeLocationAndNotExported() {
        val si = context.packageManager.getServiceInfo(ComponentName(context, GuidanceForegroundService::class.java), 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, si.foregroundServiceType)
        assertFalse(si.exported)
    }

    @Test
    fun backupIsOff() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    @Config(qualifiers = "mn")
    fun everyStringKeyResolvesInBothLanguagesToTheShippedText() {
        for (lang in Lang.entries) {
            val s = ResourceStrings(context, lang)
            val expected = TestStrings.map(lang)
            for (k in StringKey.entries) assertEquals("${k.resName} ($lang)", expected[k.resName], s[k])
        }
    }
}
