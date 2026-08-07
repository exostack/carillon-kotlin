package dev.carillon.sdk

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The three things that need a running Android app.
 *
 * Each is an interface with a real implementation beside it, for one reason: a
 * JVM unit test cannot call any of them, and the alternative to injecting them
 * is Robolectric — a third party, in an SDK that has one dependency. Behind
 * these seams the protocol logic is ordinary Kotlin and is tested as such.
 */
internal fun interface TokenSource {
  /** Null when Firebase cannot produce one: no Play Services, or offline. */
  suspend fun currentToken(): String?
}

internal fun interface Permissions {
  fun notificationsAllowed(): Boolean
}

internal fun interface Debuggability {
  fun isDebuggable(): Boolean
}

internal class FirebaseTokenSource : TokenSource {
  override suspend fun currentToken(): String? =
    try {
      suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
          if (continuation.isActive) {
            continuation.resume(if (task.isSuccessful) task.result else null)
          }
        }
      }
    } catch (error: Exception) {
      // Firebase not initialised, or Play Services missing on the device. There
      // is nothing to retry here and nothing to crash the host app over: the
      // reason reaches the log and `debugInfo()`, and registration waits for a
      // token that may arrive later through `didRotate`.
      null
    }
}

internal class AndroidPermissions(private val context: Context) : Permissions {
  override fun notificationsAllowed(): Boolean {
    // Below API 33 a notification needs no permission at all, so there is
    // nothing to be denied and the only honest answer is yes.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

    return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
  }
}

internal class AndroidDebuggability(private val context: Context) : Debuggability {
  override fun isDebuggable(): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
