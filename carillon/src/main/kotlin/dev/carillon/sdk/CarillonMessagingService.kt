package dev.carillon.sdk

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase messaging service for apps without an existing service.
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
 * If the app already has a FirebaseMessagingService, forward these callbacks
 * from it instead. Declare only one messaging service.
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
