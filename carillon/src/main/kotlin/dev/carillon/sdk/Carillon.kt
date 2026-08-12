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
 * Carillon Android SDK.
 *
 * Native, one platform dependency: Firebase Messaging, the vendor's own push
 * channel. The public surface is additive forever — this code ships inside
 * customer binaries and cannot be updated by us, so a mistake here is paid for
 * in store release cycles rather than in a deploy.
 *
 * ```kotlin
 * // Registers this device. Nobody is prompted: a push token is transport
 * // addressing, not consent.
 * Carillon.configure(context, key = "carillon_mk_live_…", debug = true)
 *
 * // A separate decision, made whenever the app has a reason to ask.
 * Carillon.requestPermission(activity)
 * ```
 *
 * Then the two forwarding calls in a `FirebaseMessagingService` and one in the
 * launcher activity. The SDK installs nothing behind the app's back; see
 * [CarillonMessagingService] for the service it ships for apps that have none.
 */
object Carillon {
  /** The SDK version reported at registration. */
  const val SDK_VERSION: String = "0.1.1"

  /** Production. Overridden for staging, and for nothing else. */
  const val DEFAULT_ENDPOINT: String = "https://api.carillon.dev"

  private val installLock = Any()
  private var installed: Engine? = null

  /**
   * The engine, built on first use.
   *
   * It exists before [configure] is called so that a callback arriving early —
   * an app launched by a tap runs its activity before anything else — is held
   * rather than dropped. Without a key it sends nothing; it just remembers.
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
   * Configures the SDK, and registers this device.
   *
   * Registration happens here, silently: no dialogue is shown and none is
   * needed. The device appears in the customer's base from its first launch,
   * carrying the permission it actually has. [requestPermission] is a separate
   * decision, made whenever the app has a reason to ask.
   *
   * Call once, early — `Application.onCreate` is the place.
   *
   * @param context any context; the application context is what is retained.
   * @param key a mobile key, `carillon_mk_live_…` or `carillon_mk_test_…`. It is
   *   public by construction — it ships inside this APK — which is why it can
   *   only register this device and report this device's events.
   * @param endpoint the API. Defaults to production; override it for staging.
   * @param debug verbose logging. Honoured only when the host application is
   *   itself debuggable: a release build refuses the flag whatever the code says,
   *   so a `true` left in shipped code logs nothing.
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
   * Shows the system's permission dialogue, and answers with what it decided.
   *
   * One question, one answer. It does not register the device — [configure]
   * already did, and this handset has been in the customer's base since its
   * first launch. What changes here is whether anything will be *displayed*, and
   * the new permission reaches the server on its own: it is part of the state,
   * so it is part of the fingerprint the registration loop compares.
   *
   * Takes an `Activity` because Android's request API does, and because a
   * dialogue belongs to a screen. iOS's equivalent takes nothing — that
   * asymmetry is the platforms', not ours. Below API 33 there is nothing to ask
   * for and nothing is shown; the answer then comes from whether notifications
   * are enabled in Settings, which is where it comes from on every version.
   *
   * Suspends until the person has answered. Returns [PushPermission.ALLOWED] or
   * [PushPermission.DENIED] — the other two states are iOS's.
   */
  @JvmStatic
  suspend fun requestPermission(activity: Activity): PushPermission =
    engine.requestPermission(AndroidPermissionRequest(activity))

  /**
   * Forwarded from `FirebaseMessagingService.onNewToken`.
   *
   * Unlike APNs, FCM hands over a string rather than bytes, so there is nothing
   * to encode: the token goes to the server exactly as Google produced it, which
   * is what its own documentation asks for.
   */
  @JvmStatic fun didRotate(token: String) = engine.setToken(token)

  /**
   * Forwarded from `FirebaseMessagingService.onMessageReceived`.
   *
   * Displaying the message is the app's business. This exists so that the SDK can
   * see what arrived, and as the place a `received` event would be reported from
   * on the day the protocol adds one.
   */
  @JvmStatic fun didReceive(message: RemoteMessage) = engine.didReceive(message.data)

  /**
   * Called from `onCreate` and `onNewIntent` with the launching intent.
   *
   * Reads `carillon.delivery_id` out of the extras and queues an open. Possession
   * of that id is the proof of receipt: it is unguessable and it travelled inside
   * this one notification, which is what makes an open rate something that cannot
   * be manufactured.
   *
   * Forward every intent. One that is not ours carries no such extra and is
   * ignored, so the app does not have to work out which is which.
   */
  @JvmStatic
  fun didOpen(intent: Intent?): Boolean {
    val extras = intent?.extras ?: return false
    val data = extras.keySet().mapNotNull { name -> extras.getString(name)?.let { name to it } }

    return engine.didOpen(data.toMap())
  }

  /**
   * Your own identifier for the person using this device.
   *
   * An attribute of the device, never an entity: one person on two handsets is
   * two devices, and both carry the same identifier.
   */
  @JvmStatic fun identify(externalId: String) = engine.identify(externalId)

  /**
   * Forgets the identifier. The device stays registered and reachable — this says
   * who is using it is no longer known, not that it should stop receiving.
   */
  @JvmStatic fun clearIdentity() = engine.identify(null)

  /**
   * Replaces the tags whole.
   *
   * The SDK holds the canonical map and the server replaces what it holds, so
   * this is the complete set every time. Merging would make removing a tag
   * impossible without inventing a word for "remove".
   */
  @JvmStatic fun setTags(tags: Map<String, TagValue>) = engine.setTags(tags)

  /** Opts the device back in. Notifications resume at the next send. */
  @JvmStatic fun optIn() = engine.setOptedIn(true)

  /**
   * Opts the device out. The row stays, so the person can be opted back in, and
   * so the customer can still see that this handset exists.
   */
  @JvmStatic fun optOut() = engine.setOptedIn(false)

  /**
   * Called when a notification sent by Carillon is opened.
   *
   * Set it once, wherever the app decides what a tap means. An open that arrived
   * before a handler was attached — a cold start, which is the most valuable tap
   * there is — is delivered as soon as one is.
   */
  @JvmStatic
  var onOpened: ((OpenedNotification) -> Unit)?
    get() = engine.currentOnOpened()
    set(value) = engine.setOnOpened(value)

  /**
   * One value, made to be pasted into a support ticket.
   *
   * Available in every build, release included. Debug logging speaks unprompted
   * and is refused outside a debuggable application; this answers when asked, and
   * answering is never the wrong thing to do.
   */
  @JvmStatic fun debugInfo(): DebugInfo = engine.debugInfo()

  /**
   * Every debug line, as it is written.
   *
   * The lines already go to logcat under the `Carillon` tag, which is where they
   * belong. This exists for the one case that cannot reach logcat: a test bench
   * showing what the SDK is doing inside the app itself, on a device in someone's
   * hand. It is behind the same debuggability check as the logging it mirrors, so
   * it cannot become a way to read the SDK's log in production.
   */
  @JvmStatic
  var onDebugLine: ((String) -> Unit)?
    get() = Log.onLine
    set(value) {
      Log.onLine = value
    }

  /**
   * What the operating system answers for itself, re-read rather than
   * remembered: a person who changes their phone's language has changed which
   * text they should be sent.
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
   * The monotonic build, as a string.
   *
   * `versionCode` was widened to a long in API 28 and the narrow accessor
   * deprecated with it; both are read here because this SDK runs from API 24. A
   * string on the wire, because iOS's `CFBundleVersion` is text and one field
   * cannot be two types.
   */
  private fun buildOf(info: PackageInfo): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      info.longVersionCode.toString()
    } else {
      @Suppress("DEPRECATION") info.versionCode.toString()
    }

  private const val PREFERENCES = "dev.carillon.sdk"
}
