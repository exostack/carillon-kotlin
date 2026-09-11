package dev.carillon.sdk

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What survives the process being killed.
 *
 * `SharedPreferences`, in the app's own storage. Everything here describes an
 * install: a device id and an FCM token that Google has already reissued to
 * nobody. Auto Backup can carry the preferences onto another device, which is
 * why the installation secret is sealed under a Keystore key before it is
 * written: the key does not travel, so the restored copy proves nothing.
 *
 * An interface because every test in this module would otherwise need a real
 * Android context, and the alternative to that is a third party.
 */
internal interface Store {
  var state: DeviceState?

  /**
   * The id the server returned. Kept for `debugInfo()`, which is the first thing
   * support asks for and the only place it is ever needed.
   */
  var installationSecret: String?
  var deviceId: String?

  /**
   * The fingerprint of the state the server has confirmed. What makes a second
   * launch with nothing changed cost no call at all.
   */
  var registeredFingerprint: String?

  var events: List<QueuedEvent>
}

/** One open, waiting to be reported. */
internal data class QueuedEvent(val type: String, val deliveryId: String, val atMs: Long) {
  fun json(): Map<String, Any?> =
    mapOf("type" to type, "delivery_id" to deliveryId, "at" to Iso8601.format(atMs))

  companion object {
    const val OPENED = "opened"
  }
}

/**
 * The instant format the API reads and writes, fixed to UTC with milliseconds.
 *
 * Pinned rather than left to a default: the API validates ISO 8601, and a device
 * in Paris must not report an open in local time with an offset the server then
 * has to guess the intent of. `SimpleDateFormat` rather than `java.time`, which
 * arrived at API 26 and this SDK supports 24.
 */
internal object Iso8601 {
  private val formatter =
    ThreadLocal.withInitial {
      SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
      }
    }

  fun format(milliseconds: Long): String = formatter.get()!!.format(Date(milliseconds))

  fun parse(text: String): Long? = runCatching { formatter.get()!!.parse(text)?.time }.getOrNull()
}

internal class SharedPreferencesStore(
  private val preferences: SharedPreferences,
  private val cipher: SecretCipher,
) : Store {
  private var unsealedSecret: String? = null

  init {
    preferences.getString(LEGACY_SECRET, null)?.let { plain ->
      installationSecret = plain
      write(LEGACY_SECRET, null)
    }
  }

  override var state: DeviceState?
    get() = preferences.getString(STATE, null)?.let(DeviceState::restore)
    set(value) {
      write(STATE, value?.stored())
    }

  override var installationSecret: String?
    get() =
      unsealedSecret
        ?: preferences.getString(SEALED_SECRET, null)?.let(cipher::open)?.also { unsealedSecret = it }
    set(value) {
      val sealed = value?.let(cipher::seal)
      unsealedSecret = if (sealed == null) value else null
      write(SEALED_SECRET, sealed)
    }

  override var deviceId: String?
    get() = preferences.getString(DEVICE_ID, null)
    set(value) {
      write(DEVICE_ID, value)
    }

  override var registeredFingerprint: String?
    get() = preferences.getString(FINGERPRINT, null)
    set(value) {
      write(FINGERPRINT, value)
    }

  override var events: List<QueuedEvent>
    get() {
      val stored = preferences.getString(EVENTS, null) ?: return emptyList()
      // A value this version cannot read is one an older or newer install wrote.
      // Dropped rather than repaired: a queue that cannot be read is a queue that
      // could never be sent either.
      val parsed = Json.parse(stored) as? List<*> ?: return emptyList()

      return parsed.mapNotNull { entry ->
        val fields = entry as? Map<*, *> ?: return@mapNotNull null
        val deliveryId = fields["delivery_id"] as? String ?: return@mapNotNull null
        val at = (fields["at"] as? String)?.let(Iso8601::parse) ?: return@mapNotNull null

        QueuedEvent(fields["type"] as? String ?: QueuedEvent.OPENED, deliveryId, at)
      }
    }
    set(value) {
      write(EVENTS, Json.encode(value.map { it.json() }))
    }

  private fun write(name: String, value: String?) {
    preferences.edit().apply { if (value == null) remove(name) else putString(name, value) }.apply()
  }

  private companion object {
    const val STATE = "state"
    const val LEGACY_SECRET = "installation_secret"
    const val SEALED_SECRET = "installation_secret_sealed"
    const val DEVICE_ID = "device_id"
    const val FINGERPRINT = "fingerprint"
    const val EVENTS = "events"
  }
}

/** The store a test uses, and the one the SDK holds before `configure`. */
internal class MemoryStore : Store {
  override var state: DeviceState? = null
  override var installationSecret: String? = null
  override var deviceId: String? = null
  override var registeredFingerprint: String? = null
  override var events: List<QueuedEvent> = emptyList()
}
