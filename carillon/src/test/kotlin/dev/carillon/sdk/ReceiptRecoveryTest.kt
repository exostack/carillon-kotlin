package dev.carillon.sdk

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptRecoveryTest {
  @After fun clean() { Carillon.engine.adopt(MemoryStore(), Permissions { true }) }

  @Test fun recoversOnlyActiveCarillonNotificationsAndKeepsTheirPostTime() {
    val context = RuntimeEnvironment.getApplication()
    val manager = context.getSystemService(NotificationManager::class.java)
    val store = MemoryStore()
    Carillon.engine.adopt(store, Permissions { true })
    store.events = emptyList()
    manager.createNotificationChannel(NotificationChannel("receipts", "Receipts", NotificationManager.IMPORTANCE_DEFAULT))
    val notification = Notification.Builder(context, "receipts").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Hello").build()
    val id = "01937b1e-0000-7000-8000-0000000000ff"
    manager.notify("carillon:$id", 1, notification)
    manager.notify("other", 2, notification)
    manager.notify("carillon:invalid", 3, notification)
    manager.notify("carillon:01937b1e-0000-7000-8000-000000000001", 4, notification)
    manager.cancel("carillon:01937b1e-0000-7000-8000-000000000001", 4)
    val at = manager.activeNotifications.first { it.tag == "carillon:$id" }.postTime
    ReceiptRecovery.start(context)
    ReceiptRecovery.start(context)
    val activity = Robolectric.buildActivity(Activity::class.java).setup()
    assertEquals(listOf(QueuedEvent(QueuedEvent.RECEIVED, id, at)), store.events)
    activity.pause().stop().destroy()
  }
}
