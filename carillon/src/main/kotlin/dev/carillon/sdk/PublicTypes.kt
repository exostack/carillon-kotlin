package dev.carillon.sdk

/**
 * OS notification display permission. Android reports ALLOWED or DENIED;
 * PROVISIONAL and UNDETERMINED are included for the shared API vocabulary.
 */
enum class PushPermission(internal val wire: String) {
  ALLOWED("allowed"),
  DENIED("denied"),
  PROVISIONAL("provisional"),
  UNDETERMINED("undetermined"),
}

/**
 * Device tag value: string, integer, decimal, or boolean. Nested values are unsupported.
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

/**
 * Creates a typed scalar tag value.
 */
fun tagOf(value: String): TagValue = TagValue.Text(value)

fun tagOf(value: Long): TagValue = TagValue.Whole(value)

fun tagOf(value: Int): TagValue = TagValue.Whole(value.toLong())

fun tagOf(value: Double): TagValue = TagValue.Fractional(value)

fun tagOf(value: Boolean): TagValue = TagValue.Flag(value)

/**
 * Opened notification with its delivery id, FCM data, and tap time in epoch milliseconds.
 */
class OpenedNotification(
  val deliveryId: String,
  val data: Map<String, String>,
  val openedAtMs: Long,
)

/**
 * Diagnostic snapshot. Includes the full mobile key and device token.
 */
class DebugInfo(
  val sdkVersion: String,
  val key: String,
  val endpoint: String,
  val token: String?,
  val deviceId: String?,
  val environment: String,
  val bundleId: String?,
  val appBuild: String?,
  val osVersion: String?,
  /**
 * allowed or denied. Null before the SDK can read Android settings.
 */
  val pushPermission: String?,
  val lastRegistrationAtMs: Long?,
  val lastRegistrationResult: String?,
  val queuedEvents: Int,
) {
  /**
 * Serializes diagnostics using API field names.
 */
  fun toJson(): String =
    Json.encode(
      mapOf(
        "sdk_version" to sdkVersion,
        "key" to key,
        "endpoint" to endpoint,
        "token" to token,
        "device_id" to deviceId,
        "environment" to environment,
        "bundle_id" to bundleId,
        "app_build" to appBuild,
        "os_version" to osVersion,
        "push_permission" to pushPermission,
        "last_registration_at" to lastRegistrationAtMs?.let(Iso8601::format),
        "last_registration_result" to lastRegistrationResult,
        "queued_events" to queuedEvents,
      )
    )

  /**
 * Formats diagnostics as aligned text; missing values appear as a dash.
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
        "bundle_id" to (bundleId ?: "—"),
        "app_build" to (appBuild ?: "—"),
        "os_version" to (osVersion ?: "—"),
        "push_permission" to (pushPermission ?: "—"),
        "last_registration_at" to (lastRegistrationAtMs?.let(Iso8601::format) ?: "—"),
        "last_registration_result" to (lastRegistrationResult ?: "—"),
        "queued_events" to queuedEvents.toString(),
      )
    val width = lines.maxOf { it.first.length }

    return lines.joinToString("\n") { (name, value) -> "${name.padEnd(width)}  $value" }
  }
}
