package dev.carillon.sdk

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationDisplayTest {
  private val context get() = RuntimeEnvironment.getApplication()
  private val manager get() = context.getSystemService(NotificationManager::class.java)
  private val received = ReceivedNotification("delivery", "Hello", "Body", mapOf("url" to "/chat"), null, "chat")
  private val raw get() = mapOf("title" to "Hello", "body" to "Body", "data" to mapOf("carillon" to "{\"delivery_id\":\"delivery\",\"thread_id\":\"chat\"}", "url" to "/chat"))

  @Before fun setup() {
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
    context.applicationInfo.icon = android.R.drawable.ic_dialog_info
    val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
    val info = ResolveInfo().apply {
      activityInfo = ActivityInfo().apply { packageName = context.packageName; name = "MainActivity" }
    }
    shadowOf(context.packageManager).addResolveInfoForIntent(launch, info)
  }

  @After fun clean() {
    Carillon.onReceived = null
    Carillon.onReceivedAsync = null
  }

  @Test fun defaultChannelAndTapPreserveTheStamp() {
    NotificationDisplay.show(context, received, raw, null, "")
    val notification = manager.activeNotifications.single().notification
    assertEquals("carillon_default", notification.channelId)
    assertEquals("chat", notification.group)
    notification.contentIntent.send()
    val intent = shadowOf(context).nextStartedActivity
    assertEquals("/chat", intent.getStringExtra("url"))
    assertEquals("{\"delivery_id\":\"delivery\",\"thread_id\":\"chat\"}", intent.getStringExtra("carillon"))
  }

  @Test fun existingChannelIsUsed() {
    manager.createNotificationChannel(NotificationChannel("chat", "Chat", NotificationManager.IMPORTANCE_HIGH))
    NotificationDisplay.show(context, received, raw + ("channel" to "chat"), null, "")
    assertEquals("chat", manager.activeNotifications.single().notification.channelId)
  }

  @Test fun suppressDoesNotPost() = runTest {
    Carillon.onReceived = { assertEquals("/chat", it.data["url"]); NotificationPresentation.SUPPRESS }
    worker().doWork()
    assertEquals(0, manager.activeNotifications.size)
  }

  @Test fun stalledAsyncHandlerDefaultsToShow() = runTest {
    Carillon.onReceivedAsync = { _, _ -> }
    worker().doWork()
    assertEquals(1, manager.activeNotifications.size)
    assertEquals(3000, testScheduler.currentTime)
  }

  @Test fun asyncSuppressIsRespected() = runTest {
    Carillon.onReceivedAsync = { _, reply -> reply(NotificationPresentation.SUPPRESS); reply(NotificationPresentation.SHOW) }
    worker().doWork()
    assertEquals(0, manager.activeNotifications.size)
  }

  @Test fun clearPreventsAnOlderDownloadFromReposting() {
    NotificationDisplay.show(context, received, raw, null, "")
    NotificationDisplay.clear(context)
    NotificationDisplay.show(context, received, raw, null, "")
    assertEquals(0, manager.activeNotifications.size)
  }

  @Test fun insecureImageIsSkipped() = runTest {
    assertNull(NotificationImage.download("http://example.com/image.png"))
  }

  @Test fun richNotificationFixtureMatchesSwift() = runTest {
    val fixture = Json.parse(javaClass.classLoader.getResourceAsStream("notification.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<*, *>
    val data = (fixture["data"] as Map<*, *>) + ("carillon" to Json.encode(fixture["carillon"]))
    Carillon.onReceived = { notification ->
      assertEquals("01937b1e-0000-7000-8000-0000000000ff", notification.deliveryId)
      assertEquals("orders", notification.threadId)
      assertEquals("https://example.com/order.png", notification.image)
      assertEquals("42", notification.data["order_id"])
      NotificationPresentation.SUPPRESS
    }
    val content = mapOf("title" to fixture["title"], "body" to fixture["body"], "data" to data, "image" to "https://example.com/order.png")
    TestListenableWorkerBuilder<NotificationDisplayWorker>(context)
      .setInputData(Data.Builder().putString("content", Json.encode(content)).build()).build().doWork()
  }

  private fun worker(): NotificationDisplayWorker = TestListenableWorkerBuilder<NotificationDisplayWorker>(context)
    .setInputData(Data.Builder().putString("content", Json.encode(raw)).build()).build()
}
