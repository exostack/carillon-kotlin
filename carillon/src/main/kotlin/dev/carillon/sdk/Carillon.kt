package dev.carillon.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import com.google.firebase.messaging.RemoteMessage
import java.util.Locale
import java.util.TimeZone

/**
 * Carillon Android SDK. Call [configure] at app startup, then forward Firebase callbacks
 * and launcher intents. See the README for setup.
 */
object Carillon {
  /** The SDK version reported at registration. */
  const val SDK_VERSION: String = "0.1.1"

  /**
 * Default API endpoint. Override for staging or local development.
 */
  const val DEFAULT_ENDPOINT: String = "https://api.carillon.dev"

  private val installLock = Any()
  private var installed: Engine? = null

  /**
 * Created before configure so early notification opens can be buffered.
 * No requests are sent until a key and token are available.
 */
  internal val engine: Engine
    get() =
      synchronized(installLock) {
        installed
          ?: Engine(
              store = MemoryStore(),
              transport = UrlConnectionTransport(DEFAULT_ENDPOINT),
              clock = SystemClock(),
              tokenSource = FirebaseTokenSource(),
              // Nothing to ask a system that is not here yet. `configure`
              // replaces this with the real one before anything reads it.
              permissions = Permissions { true },
            )
            .also { installed = it }
      }

  /**
 * Starts token acquisition and device registration without a permission prompt.
 * Call once in Application.onCreate. Registration requires an FCM token and network access.
 * Use [requestPermission] separately to request notification display permission.
 *
 * @param context application context is retained.
 * @param key mobile API key from the Carillon dashboard.
 * @param endpoint API base URL. Defaults to production.
 * @param debug enables logging only when the host app is debuggable.
 */
  @JvmStatic
  @JvmOverloads
  fun configure(
    context: Context,
    key: String,
    endpoint: String = DEFAULT_ENDPOINT,
    debug: Boolean = false,
  ) {
    val application = context.applicationContext
    val preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val debuggable = AndroidDebuggability(application).isDebuggable()

    // The same engine throughout, given the real storage now that there is a
    // context for it. Anything it was already holding — a tap that arrived
    // before this call, a handler already attached — stays where it is.
    engine.adopt(SharedPreferencesStore(preferences), AndroidPermissions(application))
    engine.configure(
      key = key,
      endpoint = endpoint,
      debug = debug && debuggable,
      transport = UrlConnectionTransport(endpoint),
    )
    refreshAttributes(application)

    // The token, asked for now and with nothing asked of anybody. It arrives on
    // a coroutine of the engine's own, and the registration loop absorbs the
    // delay exactly as it absorbs a device that starts up offline.
    engine.acquireToken()
  }

  /**
 * Requests notification permission and syncs the result to the server.
 * Suspends until the request completes. Returns [PushPermission.ALLOWED] or
 * [PushPermission.DENIED]. Below API 33, reads notification settings without a prompt.
 */
  @JvmStatic
  suspend fun requestPermission(activity: Activity): PushPermission =
    engine.requestPermission(AndroidPermissionRequest(activity))

  /**
 * Forward FirebaseMessagingService.onNewToken to update the FCM token.
 */
  @JvmStatic fun didRotate(token: String) = engine.setToken(token)

  /**
 * Forward FirebaseMessagingService.onMessageReceived. Does not display notifications
 * or report received events.
 */
  @JvmStatic fun didReceive(message: RemoteMessage) = engine.didReceive(message.data)

  /**
 * Forward the launcher intent from onCreate and onNewIntent.
 * Queues an open when the payload contains a Carillon delivery id.
 * Returns false if no valid delivery id is present.
 */
  @JvmStatic
  fun didOpen(intent: Intent?): Boolean {
    val extras = intent?.extras ?: return false
    val data = extras.keySet().mapNotNull { name -> extras.getString(name)?.let { name to it } }

    return engine.didOpen(data.toMap())
  }

  /**
 * Sets the external user id for this device. Multiple devices may share an id.
 */
  @JvmStatic fun identify(externalId: String) = engine.identify(externalId)

  /**
 * Clears the external user id without opting out or deleting the device.
 */
  @JvmStatic fun clearIdentity() = engine.identify(null)

  /**
 * Replaces all device tags. Omitted tags are removed.
 */
  @JvmStatic fun setTags(tags: Map<String, TagValue>) = engine.setTags(tags)

  /**
 * Sets opted_in to true and syncs it to the server. Does not change OS permission.
 */
  @JvmStatic fun optIn() = engine.setOptedIn(true)

  /**
 * Sets opted_in to false and syncs it to the server. Keeps the device registered.
 */
  @JvmStatic fun optOut() = engine.setOptedIn(false)

  /**
 * Handles notification opens. Opens received before a handler is attached are replayed when it is set.
 */
  @JvmStatic
  var onOpened: ((OpenedNotification) -> Unit)?
    get() = engine.currentOnOpened()
    set(value) = engine.setOnOpened(value)

  /**
 * Returns SDK configuration, registration status, and queued-event count. Available in release builds.
 */
  @JvmStatic fun debugInfo(): DebugInfo = engine.debugInfo()

  /**
 * Receives debug log lines, also written to logcat under the Carillon tag.
 * Emits only when debug logging is enabled in a debuggable app.
 */
  @JvmStatic
  var onDebugLine: ((String) -> Unit)?
    get() = Log.onLine
    set(value) {
      Log.onLine = value
    }

  /**
 * Refreshes device attributes from current system settings.
 */
  private fun refreshAttributes(context: Context) {
    val info =
      try {
        context.packageManager.getPackageInfo(context.packageName, 0)
      } catch (error: Exception) {
        null
      }

    engine.refreshDeviceAttributes(
      timezoneId = TimeZone.getDefault().id,
      // BCP 47, which is what the server validates. `Locale.toString()` produces
      // `fr_FR` with an underscore, and the server would take it for a tag it
      // does not know.
      locale = Locale.getDefault().toLanguageTag(),
      appVersion = info?.versionName,
      appBuild = info?.let(::buildOf),
      bundleId = context.packageName,
      // Verbatim. `RELEASE` is what a person reads on their phone — "14", and
      // "15 QPR1" on the builds where Google says so — while `SDK_INT` is the
      // API level, a different number that answers a different question.
      osVersion = Build.VERSION.RELEASE,
    )
  }

  /**
 * Uses longVersionCode from API 28; older devices use versionCode.
 * Encoded as a string to match the shared API field.
 */
  private fun buildOf(info: PackageInfo): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      info.longVersionCode.toString()
    } else {
      @Suppress("DEPRECATION") info.versionCode.toString()
    }

  private const val PREFERENCES = "dev.carillon.sdk"
}
