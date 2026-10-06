package dev.carillon.sdk

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import java.util.UUID

internal object ReceiptRecovery : Application.ActivityLifecycleCallbacks {
  private var registered: Application? = null

  @Synchronized fun start(context: Context) {
    val application = context.applicationContext as? Application ?: return
    if (registered !== application) {
      registered?.unregisterActivityLifecycleCallbacks(this)
      registered = application
      application.registerActivityLifecycleCallbacks(this)
    }
    recover(application)
  }

  private fun recover(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    for (notification in manager.activeNotifications) {
      val id = notification.tag?.takeIf { it.startsWith("carillon:") }?.removePrefix("carillon:") ?: continue
      if (runCatching { UUID.fromString(id).toString().equals(id, ignoreCase = true) }.getOrDefault(false).not()) continue
      Carillon.engine.didReceive(mapOf("carillon" to "{\"delivery_id\":\"$id\"}"), notification.postTime)
    }
  }

  override fun onActivityResumed(activity: Activity) = recover(activity)
  override fun onActivityCreated(activity: Activity, state: Bundle?) {}
  override fun onActivityStarted(activity: Activity) {}
  override fun onActivityPaused(activity: Activity) {}
  override fun onActivityStopped(activity: Activity) {}
  override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
  override fun onActivityDestroyed(activity: Activity) {}
}
