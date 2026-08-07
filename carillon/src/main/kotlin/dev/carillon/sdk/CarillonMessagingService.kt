package dev.carillon.sdk

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * The service, for apps that do not already have one.
 *
 * Declare it in the manifest and token rotation is handled:
 *
 * ```xml
 * <service android:name="dev.carillon.sdk.CarillonMessagingService"
 *          android:exported="false">
 *   <intent-filter>
 *     <action android:name="com.google.firebase.MESSAGING_EVENT" />
 *   </intent-filter>
 * </service>
 * ```
 *
 * An app that already has a `FirebaseMessagingService` keeps it and forwards two
 * calls instead — Firebase dispatches to one service per application, so two
 * declarations mean one of them silently never runs. The forwarding is the same
 * explicitness the iOS side asks of an app delegate: two visible lines rather
 * than something installed behind the app's back.
 *
 * ```kotlin
 * override fun onNewToken(token: String) = Carillon.didRotate(token)
 * override fun onMessageReceived(message: RemoteMessage) = Carillon.didReceive(message)
 * ```
 */
open class CarillonMessagingService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    super.onNewToken(token)
    Carillon.didRotate(token)
  }

  override fun onMessageReceived(message: RemoteMessage) {
    super.onMessageReceived(message)
    Carillon.didReceive(message)
  }
}
