package dev.carillon.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.carillon.sdk.Carillon

/**
 * The bench's own service — the second of the two documented integrations.
 *
 * An app that already has a `FirebaseMessagingService` keeps it and forwards
 * two calls; this class is that pattern, verbatim, so the bench proves it works
 * rather than only documenting it. The first pattern — declaring the SDK's own
 * service — needs no code at all.
 *
 * The rest is display, which is deliberately the app's business and not the
 * SDK's. A notification-type message reaches this method only while the app is
 * in the foreground — backgrounded, the system displays it and this never runs
 * — so everything shown from here is the foreground case the system would have
 * swallowed silently.
 */
class ExampleMessagingService : FirebaseMessagingService() {

  override fun onNewToken(token: String) {
    super.onNewToken(token)
    Log.d(TAG, "onNewToken: forwarding rotated token to Carillon")
    Carillon.didRotate(token)
  }

  override fun onMessageReceived(message: RemoteMessage) {
    super.onMessageReceived(message)

    // The reserved key: every Carillon-sent notification carries its delivery
    // id under `carillon` in the data map. Its presence is what identifies the
    // sender; its value is the id the opened event will prove possession of.
    val stamp = message.data["carillon"]
    Log.d(
      TAG,
      "onMessageReceived (foreground): title=${message.notification?.title} " +
        "body=${message.notification?.body} " +
        if (stamp != null) "carillon=$stamp" else "no carillon stamp — not ours",
    )

    Carillon.didReceive(message)

    val alert = message.notification ?: return
    show(alert.title ?: "", alert.body ?: "")
  }

  private fun show(title: String, body: String) {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL, "Foreground deliveries", NotificationManager.IMPORTANCE_HIGH),
      )
    }

    val notification =
      (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          Notification.Builder(this, CHANNEL)
        } else {
          @Suppress("DEPRECATION") Notification.Builder(this)
        })
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(body)
        .setAutoCancel(true)
        .build()

    manager.notify(System.currentTimeMillis().toInt(), notification)
    Log.d(TAG, "displayed foreground notification: $title")
  }

  private companion object {
    const val TAG = "CarillonExample"
    const val CHANNEL = "foreground"
  }
}
