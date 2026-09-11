package dev.carillon.sdk

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PermissionApiTest {
  private val context get() = RuntimeEnvironment.getApplication()
  private val manager get() = context.getSystemService(NotificationManager::class.java)
  private val permission = Manifest.permission.POST_NOTIFICATIONS

  @After fun clean() {
    Carillon.engine.adopt(MemoryStore(), Permissions { true })
  }

  @Test fun getPermissionReadsSettingsAndSyncsWithoutPrompting() {
    Carillon.engine.adopt(MemoryStore(), AndroidPermissions(context))

    assertEquals(PushPermission.ALLOWED, Carillon.getPermission())
    assertEquals("allowed", Carillon.debugInfo().pushPermission)

    shadowOf(manager).setNotificationsEnabled(false)

    assertEquals(PushPermission.DENIED, Carillon.getPermission())
    assertEquals("denied", Carillon.debugInfo().pushPermission)
    assertEquals(0, shadowOf(context).nextStartedActivity?.let { 1 } ?: 0)
  }

  @Test fun canRequestPermissionIsFalseBelowApi33() {
    assertFalse(Carillon.canRequestPermission(activity()))
  }

  @Test @Config(sdk = [33]) fun canRequestPermissionUntilTheSystemStopsAsking() {
    val activity = activity()

    assertTrue(Carillon.canRequestPermission(activity))

    PermissionPrompt.markAsked(context)
    assertFalse(Carillon.canRequestPermission(activity))

    shadowOf(context.packageManager).setShouldShowRequestPermissionRationale(permission, true)
    assertTrue(Carillon.canRequestPermission(activity))

    shadowOf(context).grantPermissions(permission)
    assertFalse(Carillon.canRequestPermission(activity))
  }

  @Test @Config(sdk = [33]) fun requestingThePermissionRecordsThatTheSdkAsked() {
    assertFalse(PermissionPrompt.wasAsked(context))

    AndroidPermissionRequest(activity()).let { request ->
      kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeoutOrNull(1_000) { request.show() }
      }
    }

    assertTrue(PermissionPrompt.wasAsked(context))
  }

  @Test fun openNotificationSettingsTargetsTheAppNotificationScreen() {
    Carillon.openNotificationSettings(context)

    val started = shadowOf(context).nextStartedActivity
    assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
    assertEquals(context.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  @Test fun openNotificationSettingsFallsBackToAppDetails() {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
    shadowOf(context.packageManager).addResolveInfoForIntent(details, android.content.pm.ResolveInfo().apply {
      activityInfo = android.content.pm.ActivityInfo().apply { packageName = "com.android.settings"; name = "Details" }
    })
    shadowOf(context).checkActivities(true)

    Carillon.openNotificationSettings(context)

    val started = shadowOf(context).nextStartedActivity
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
    assertEquals("package:${context.packageName}", started.dataString)
  }

  private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
}
