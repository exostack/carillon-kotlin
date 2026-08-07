package dev.carillon.sdk

/**
 * Everything the server is told about this device.
 *
 * One value, held whole, sent whole. The protocol makes the SDK the canonical
 * holder of this state and the registration call a replacement rather than a
 * patch — which is what makes re-registration free, and what makes an app that
 * has been offline for a week correct again with one call rather than several.
 */
internal data class DeviceState(
  /**
   * Absent until Firebase hands one over. Nothing is sent before it exists: a
   * registration without a token names no device.
   */
  val token: String? = null,
  /**
   * Always `android` in this SDK. A field rather than a constant because the
   * conformance vectors are shared with the other SDKs, and a replay has to be
   * able to say which one it is speaking for.
   */
  val platform: String = ANDROID,
  /**
   * Always `production`. FCM has no sandbox — the field exists for iOS, and
   * Android declares the only value it has. A field, again, so the shared
   * vectors can be replayed.
   */
  val environment: String = PRODUCTION,
  val externalId: String? = null,
  val tags: Map<String, TagValue> = emptyMap(),
  val timezoneId: String? = null,
  val locale: String? = null,
  val appVersion: String? = null,
  val sdkVersion: String? = null,
  val optedIn: Boolean = true,
) {
  /**
   * The body of `POST /v1/devices`.
   *
   * Every field is present on every call, including the null ones, and that is
   * deliberate on both sides: the server reads an absent field as "unchanged"
   * and an explicit null as "erase". Since this SDK holds the whole truth about
   * the device, sending the whole truth is the only description that stays
   * correct — `clearIdentity()` has to reach the server as a null, and it can
   * only do that if nulls are sent.
   */
  fun registrationBody(): Map<String, Any?> =
    mapOf(
      "token" to (token ?: ""),
      "platform" to platform,
      "environment" to environment,
      "external_id" to externalId,
      "tags" to tags.mapValues { it.value.json },
      "timezone_id" to timezoneId,
      "locale" to locale,
      "app_version" to appVersion,
      "sdk_version" to sdkVersion,
      "opted_in" to optedIn,
    )

  /**
   * What "the server already knows this" means.
   *
   * Compared as the serialised body rather than field by field: the question is
   * whether another call would tell the server anything new, and the body is
   * exactly that question. A field added to the table later is covered without
   * anyone remembering to extend a comparison.
   */
  fun fingerprint(): String = Json.encode(registrationBody())

  /** What goes into the preferences. The token is nullable here, unlike on the wire. */
  fun stored(): String =
    Json.encode(registrationBody().toMutableMap().also { it["token"] = token })

  companion object {
    const val ANDROID = "android"
    const val PRODUCTION = "production"

    fun restore(text: String): DeviceState? {
      val parsed = Json.parse(text) as? Map<*, *> ?: return null
      val tags =
        (parsed["tags"] as? Map<*, *> ?: emptyMap<Any?, Any?>()).mapNotNull { (name, value) ->
          val tag = TagValue.of(value) ?: return@mapNotNull null

          name.toString() to tag
        }

      return DeviceState(
        token = parsed["token"] as? String,
        platform = parsed["platform"] as? String ?: ANDROID,
        environment = parsed["environment"] as? String ?: PRODUCTION,
        externalId = parsed["external_id"] as? String,
        tags = tags.toMap(),
        timezoneId = parsed["timezone_id"] as? String,
        locale = parsed["locale"] as? String,
        appVersion = parsed["app_version"] as? String,
        sdkVersion = parsed["sdk_version"] as? String,
        optedIn = parsed["opted_in"] as? Boolean ?: true,
      )
    }
  }
}
