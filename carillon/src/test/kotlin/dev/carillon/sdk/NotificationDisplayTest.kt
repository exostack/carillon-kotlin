package dev.carillon.sdk

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    Carillon.onOpened = null
    NotificationImage.fetch = NotificationImage::download
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

  @Test fun aFetchedImageIsShownAsABigPicture() = runTest {
    val fetched = ArrayList<String>()
    NotificationImage.fetch = { source -> fetched.add(source); Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) }

    worker(raw + ("image" to "https://example.com/order.png")).doWork()

    val notification = manager.activeNotifications.single().notification
    assertEquals(listOf("https://example.com/order.png"), fetched)
    assertEquals(Notification.BigPictureStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
    assertNotNull(notification.extras.get(Notification.EXTRA_PICTURE))
  }

  @Test fun aFailedImageKeepsTheText() = runTest {
    NotificationImage.fetch = { null }

    worker(raw + ("image" to "https://example.com/order.png")).doWork()

    val notification = manager.activeNotifications.single().notification
    assertEquals(Notification.BigTextStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
  }

  @Test fun tappingThePostedNotificationQueuesAnOpen() {
    var opened: OpenedNotification? = null
    Carillon.onOpened = { opened = it }
    val before = Carillon.debugInfo().queuedEvents

    NotificationDisplay.show(context, received, raw, null, "")
    manager.activeNotifications.single().notification.contentIntent.send()

    assertTrue(Carillon.didOpen(shadowOf(context).nextStartedActivity))
    assertEquals("delivery", opened?.deliveryId)
    assertEquals(mapOf("carillon" to "{\"delivery_id\":\"delivery\",\"thread_id\":\"chat\"}", "url" to "/chat"), opened?.data)
    assertEquals(before + 1, Carillon.debugInfo().queuedEvents)
  }

  @Test @Config(sdk = [33]) fun nothingIsPostedWithoutThePermissionOnApi33() {
    shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

    NotificationDisplay.show(context, received, raw, null, "")
    assertEquals(0, manager.activeNotifications.size)

    shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    NotificationDisplay.show(context, received, raw, null, "")
    assertEquals(1, manager.activeNotifications.size)
  }

  @Test fun theCustomSmallIconIsUsedWhenTheAppProvidesOne() {
    val custom = context.resources.getIdentifier("carillon_notification_icon", "drawable", context.packageName)
    assertNotEquals(0, custom)

    NotificationDisplay.show(context, received, raw, null, "")

    assertEquals(custom, manager.activeNotifications.single().notification.smallIcon.resId)
  }

  @Test fun theAppIconIsTheFallbackSmallIcon() {
    val resources = context.resources
    val withoutCustomIcon = object : android.content.ContextWrapper(context) {
      @Suppress("DEPRECATION")
      override fun getResources() = object : android.content.res.Resources(resources.assets, resources.displayMetrics, resources.configuration) {
        override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int =
          if (name == "carillon_notification_icon") 0 else resources.getIdentifier(name, defType, defPackage)
      }
    }

    NotificationDisplay.show(withoutCustomIcon, received, raw, null, "")

    assertEquals(android.R.drawable.ic_dialog_info, manager.activeNotifications.single().notification.smallIcon.resId)
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

  @Test fun receivedHandlerGetsDecodedDataWithoutLosingRawValues() = runTest {
    val encoded = """{"items":[true,null,{"name":"é"}]}"""
    var called = false
    Carillon.onReceived = {
      called = true
      assertEquals(encoded, it.data["order"])
      assertEquals(mapOf("items" to listOf(true, null, mapOf("name" to "é"))), it.structuredData["order"])
      assertEquals(false, it.structuredData.containsKey("carillon"))
      NotificationPresentation.SUPPRESS
    }
    worker(raw + ("data" to mapOf("carillon" to """{"delivery_id":"delivery","json_keys":["order"]}""", "order" to encoded))).doWork()
    assertTrue(called)
  }

  private fun worker(content: Map<String, Any?> = raw): NotificationDisplayWorker = TestListenableWorkerBuilder<NotificationDisplayWorker>(context)
    .setInputData(Data.Builder().putString("content", Json.encode(content)).build()).build()
}
