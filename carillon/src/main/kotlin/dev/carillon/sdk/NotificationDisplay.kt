package dev.carillon.sdk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.RemoteMessage
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

public enum class NotificationPresentation { SHOW, SUPPRESS }

public data class ReceivedNotification(
  val deliveryId: String?,
  val title: String?,
  val body: String?,
  val data: Map<String, String>,
  val image: String?,
  val threadId: String?,
)

internal object NotificationDisplay {
  const val TAG = "carillon.notifications"
  private const val DEFAULT_CHANNEL = "carillon_default"
  private val lock = Any()

  fun enqueue(context: Context, message: RemoteMessage) {
    val alert = message.notification
    val stamp = message.data["carillon"]?.let(Json::parse) as? Map<*, *>
    val id = stamp?.get("delivery_id") as? String ?: UUID.randomUUID().toString()
    val content = Json.encode(mapOf(
      "data" to message.data, "title" to alert?.title, "body" to alert?.body,
      "image" to (alert?.imageUrl?.toString() ?: stamp?.get("image")),
      "channel" to alert?.channelId, "sound" to alert?.sound,
      "badge" to alert?.notificationCount, "priority" to message.priority,
    ))
    val generation = context.getSharedPreferences(TAG, Context.MODE_PRIVATE).getString("generation", "") ?: ""
    val request = OneTimeWorkRequestBuilder<NotificationDisplayWorker>()
      .setInputData(Data.Builder().putString("content", content).putString("generation", generation).build())
      .addTag(TAG).build()
    WorkManager.getInstance(context).enqueueUniqueWork("$TAG.$id", ExistingWorkPolicy.KEEP, request)
  }

  fun clear(context: Context) = synchronized(lock) {
    context.getSharedPreferences(TAG, Context.MODE_PRIVATE).edit().putString("generation", UUID.randomUUID().toString()).commit()
    WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    context.getSystemService(NotificationManager::class.java).cancelAll()
  }

  fun show(context: Context, received: ReceivedNotification, raw: Map<*, *>, image: Bitmap?, generation: String) = synchronized(lock) {
    val current = context.getSharedPreferences(TAG, Context.MODE_PRIVATE).getString("generation", "") ?: ""
    if (generation != current) return@synchronized
    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@synchronized
    val manager = context.getSystemService(NotificationManager::class.java)
    var channel = raw["channel"] as? String ?: DEFAULT_CHANNEL
    if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(channel) == null) {
      channel = DEFAULT_CHANNEL
      manager.createNotificationChannel(NotificationChannel(channel, "Notifications", NotificationManager.IMPORTANCE_DEFAULT))
    }
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return@synchronized
    launch.action = "${context.packageName}.carillon.OPEN.${received.deliveryId}"
    launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val data = raw["data"] as? Map<*, *> ?: emptyMap<String, String>()
    for ((key, value) in data) if (key is String && value is String) launch.putExtra(key, value)
    val pending = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val icon = context.resources.getIdentifier("carillon_notification_icon", "drawable", context.packageName)
    val notification = NotificationCompat.Builder(context, channel)
      .setSmallIcon(if (icon != 0) icon else context.applicationInfo.icon)
      .setContentTitle(received.title).setContentText(received.body)
      .setAutoCancel(true).setContentIntent(pending)
      .setPriority(if ((raw["priority"] as? Number)?.toInt() == RemoteMessage.PRIORITY_HIGH) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
    received.threadId?.let(notification::setGroup)
    (raw["badge"] as? Number)?.toInt()?.let(notification::setNumber)
    (raw["sound"] as? String)?.let { sound ->
      val resource = context.resources.getIdentifier(sound.substringBeforeLast('.'), "raw", context.packageName)
      if (sound == "default") notification.setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
      else if (resource != 0) notification.setSound(Uri.parse("android.resource://${context.packageName}/$resource"))
    }
    if (image != null) notification.setStyle(NotificationCompat.BigPictureStyle().bigPicture(image))
    else received.body?.let { notification.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
    manager.notify(received.deliveryId, 0, notification.build())
  }
}

class NotificationDisplayWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
  override suspend fun doWork(): Result {
    val raw = inputData.getString("content")?.let(Json::parse) as? Map<*, *> ?: return Result.failure()
    val data = (raw["data"] as? Map<*, *>)?.entries?.mapNotNull { (key, value) -> if (key is String && value is String) key to value else null }?.toMap() ?: emptyMap()
    val stamp = data["carillon"]?.let(Json::parse) as? Map<*, *>
    val received = ReceivedNotification(stamp?.get("delivery_id") as? String, raw["title"] as? String, raw["body"] as? String, data - "carillon", raw["image"] as? String, stamp?.get("thread_id") as? String)
    val async = Carillon.onReceivedAsync
    val decision = if (async == null) Carillon.onReceived?.invoke(received) ?: NotificationPresentation.SHOW else {
      withTimeoutOrNull(3000) {
        suspendCancellableCoroutine { continuation ->
          val once = java.util.concurrent.atomic.AtomicBoolean(false)
          async(received) { answer -> if (once.compareAndSet(false, true) && continuation.isActive) continuation.resume(answer) }
        }
      } ?: NotificationPresentation.SHOW
    }
    if (decision == NotificationPresentation.SUPPRESS || received.deliveryId == null) return Result.success()
    val image = received.image?.let { NotificationImage.download(it) }
    if (!isStopped) NotificationDisplay.show(applicationContext, received, raw, image, inputData.getString("generation") ?: "")
    return Result.success()
  }
}
