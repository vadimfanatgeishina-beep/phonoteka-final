package com.phonoteka.security
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
class InviteManifestContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun trackInviteIsNotExportedAndCameraIsOptionalAndDeclared() {
        val activity = context.packageManager.getActivityInfo(ComponentName(context, com.phonoteka.TrackInviteActivity::class.java), 0)
        assertFalse(activity.exported)
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_CONFIGURATIONS)
        assertTrue(info.requestedPermissions.contains(Manifest.permission.CAMERA))
        val feature = info.reqFeatures.first { it.name == "android.hardware.camera.any" }
        assertEquals(0, feature.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED)
    }

    /** ЛР14: единственная функциональная exported-activity — InviteActivity; кроме неё только LAUNCHER MainActivity. */
    @Test fun onlyInviteAndLauncherAreExported() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
        val all = info.activities.filter { it.exported }.map { it.name }.toSet()
        // debug-сборка подмешивает activity из ui-tooling / ui-test-manifest; в release их нет
        val tooling = setOf("androidx.compose.ui.tooling.PreviewActivity", "androidx.activity.ComponentActivity")
        assertTrue("лишние exported: ${all - tooling}", (all - tooling).all { it.startsWith("com.phonoteka.") })
        if (!com.phonoteka.BuildConfig.DEBUG) assertTrue("tooling в release: $all", (all intersect tooling).isEmpty())
        val exported = all.filter { it.startsWith("com.phonoteka.") }.toSet()
        assertEquals(setOf("com.phonoteka.InviteActivity", "com.phonoteka.MainActivity"), exported)
    }

    @Test fun inviteLinkResolvesToInviteActivityAndDetailsLinkIsGone() {
        val pm = context.packageManager
        val invite = Intent(Intent.ACTION_VIEW, Uri.parse("phonoteka://invite?token=abcDEF12_-")).setPackage(context.packageName)
        assertEquals("com.phonoteka.InviteActivity", pm.queryIntentActivities(invite, 0).single().activityInfo.name)
        val details = Intent(Intent.ACTION_VIEW, Uri.parse("phonoteka://details/1")).setPackage(context.packageName)
        assertTrue(pm.queryIntentActivities(details, 0).isEmpty())
    }
}
