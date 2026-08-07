package dev.carillon.sdk

/**
 * What `register()` resolved to.
 *
 * Two cases and no error: neither is a failure the caller can retry, and both
 * are things an app legitimately branches on. A thrown exception would make a
 * person declining notifications look like a bug in the integration.
 *
 * There is no simulator case, unlike iOS: an Android emulator with Play Services
 * issues a real FCM token and receives real notifications, so there is nothing
 * to warn anybody about.
 */
enum class RegistrationOutcome {
  /** Permission is in place and a token has been asked for. */
  REGISTERED,

  /**
   * The person said no. On API 33 and above that is the `POST_NOTIFICATIONS`
   * runtime permission; below it, notifications need no permission and this
   * cannot occur.
   */
  DENIED,
}

/**
 * A tag value, as the API defines it: a flat scalar and nothing else.
 *
 * A sealed class rather than `Any`, so a nested value is refused where it is
 * written instead of coming back as a 422 from a server the customer cannot see.
 */
sealed class TagValue {
  data class Text(val value: String) : TagValue()

  data class Whole(val value: Long) : TagValue()

  data class Fractional(val value: Double) : TagValue()

  data class Flag(val value: Boolean) : TagValue()

  internal val json: Any
    get() =
      when (this) {
        is Text -> value
        is Whole -> value
        is Fractional -> value
        is Flag -> value
      }

  companion object {
    /** Reads back what [json] wrote, which is how a stored map is restored. */
    internal fun of(value: Any?): TagValue? =
      when (value) {
        is String -> Text(value)
        is Boolean -> Flag(value)
        is Number ->
          if (value.toDouble() == Math.floor(value.toDouble()) && !value.toDouble().isInfinite()) {
            Whole(value.toLong())
          } else {
            Fractional(value.toDouble())
          }
        else -> null
      }
  }
}

/** Shorthand, so a call site reads as the map it is. */
fun tagOf(value: String): TagValue = TagValue.Text(value)

fun tagOf(value: Long): TagValue = TagValue.Whole(value)

fun tagOf(value: Int): TagValue = TagValue.Whole(value.toLong())

fun tagOf(value: Double): TagValue = TagValue.Fractional(value)

fun tagOf(value: Boolean): TagValue = TagValue.Flag(value)

/**
 * An open, handed to the app.
 *
 * The full payload is included because the customer's own keys travel in it and
 * the destination of a tap is theirs to decide. Carillon carries the data and
 * takes no position on what it means.
 */
class OpenedNotification(
  val deliveryId: String,
  val data: Map<String, String>,
  val openedAtMs: Long,
)

/**
 * One call, one value, made to be pasted into a support ticket.
 *
 * The key appears whole. A mobile key ships inside every copy of the app, so
 * anyone holding the APK already has it, and truncating it here would only cost
 * the support engineer the one identifier that tells them which app they are
 * looking at.
 */
class DebugInfo(
  val sdkVersion: String,
  val key: String,
  val endpoint: String,
  val token: String?,
  val deviceId: String?,
  val environment: String,
  val lastRegistrationAtMs: Long?,
  val lastRegistrationResult: String?,
  val queuedEvents: Int,
) {
  /** The same field names the API uses, so a ticket and a log line agree. */
  fun toJson(): String =
    Json.encode(
      mapOf(
        "sdk_version" to sdkVersion,
        "key" to key,
        "endpoint" to endpoint,
        "token" to token,
        "device_id" to deviceId,
        "environment" to environment,
        "last_registration_at" to lastRegistrationAtMs?.let(Iso8601::format),
        "last_registration_result" to lastRegistrationResult,
        "queued_events" to queuedEvents,
      )
    )

  /**
   * Rendered rather than dumped: this is read by a person, in a ticket, and
   * `null` on every other line is noise they have to see past.
   */
  override fun toString(): String {
    val lines =
      listOf(
        "sdk_version" to sdkVersion,
        "key" to key.ifEmpty { "—" },
        "endpoint" to endpoint,
        "token" to (token ?: "—"),
        "device_id" to (deviceId ?: "—"),
        "environment" to environment,
        "last_registration_at" to (lastRegistrationAtMs?.let(Iso8601::format) ?: "—"),
        "last_registration_result" to (lastRegistrationResult ?: "—"),
        "queued_events" to queuedEvents.toString(),
      )
    val width = lines.maxOf { it.first.length }

    return lines.joinToString("\n") { (name, value) -> "${name.padEnd(width)}  $value" }
  }
}
