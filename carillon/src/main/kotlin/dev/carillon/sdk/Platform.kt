package dev.carillon.sdk

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The parts that need a running Android app.
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
  /**
   * Whether a notification would actually be shown.
   *
   * One question, asked of `areNotificationsEnabled()`, which is wider than the
   * runtime permission and subsumes it: it is false for an app that was refused
   * on API 33 and above, and equally false for one whose notifications were
   * switched off in Settings — which no permission check can see. That second
   * case is the one a customer opens a ticket about.
   */
  fun notificationsEnabled(): Boolean
}

internal fun interface Debuggability {
  fun isDebuggable(): Boolean
}

/**
 * Raises the system's permission dialogue.
 *
 * A seam of its own rather than a method on [Permissions], because reading a
 * state and asking a person are not the same kind of act: the first is free and
 * happens on every launch, the second happens once and is shown to somebody.
 * Separating them is what lets a test assert that configuring the SDK never
 * does the second.
 */
internal fun interface PermissionRequest {
  /** Returns when the person has answered, or at once if nothing was asked. */
  suspend fun show()
}

internal class AndroidPermissionRequest(private val activity: Activity) : PermissionRequest {
  override suspend fun show() {
    // Below API 33 a notification needs no permission, so there is nothing to
    // ask for. Whether anything will be shown is then a Settings question, and
    // `areNotificationsEnabled()` is what answers it.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    PermissionPrompt.markAsked(activity)
    PermissionRequestActivity.show(activity)
  }
}

/**
 * Whether this SDK has ever raised the dialogue. Android 13 stops showing it
 * after two refusals and says nothing, and this flag is half of what tells
 * `canRequestPermission` that asking again would show nothing.
 */
internal object PermissionPrompt {
  private const val ASKED = "permission_asked"

  fun wasAsked(context: Context): Boolean = preferences(context).getBoolean(ASKED, false)

  fun markAsked(context: Context) {
    preferences(context).edit().putBoolean(ASKED, true).apply()
  }

  private fun preferences(context: Context) =
    context.getSharedPreferences(Carillon.PREFERENCES, Context.MODE_PRIVATE)
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

/**
 * `NotificationManagerCompat.areNotificationsEnabled()`: false for an app refused
 * on API 33 and above, and equally false for one whose notifications were
 * switched off in Settings — which no permission check can see.
 */
internal class AndroidPermissions(private val context: Context) : Permissions {
  override fun notificationsEnabled(): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()
}

internal class AndroidDebuggability(private val context: Context) : Debuggability {
  override fun isDebuggable(): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
