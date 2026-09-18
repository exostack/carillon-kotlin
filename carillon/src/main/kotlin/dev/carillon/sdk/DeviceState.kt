package dev.carillon.sdk

/**
 * Everything the server is told about this device.
 *
 * Device attributes are snapshots; tags hold only unacknowledged per-key changes.
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
  val tags: Map<String, TagValue?> = emptyMap(),
  val timezoneId: String? = null,
  val locale: String? = null,
  val appVersion: String? = null,
  /**
   * `versionCode`, as a string. The wire carries one type for both platforms and
   * iOS's `CFBundleVersion` is text; a number here would make the same field two
   * shapes. Distinct from [appVersion], which several builds share.
   */
  val appBuild: String? = null,
  val bundleId: String? = null,
  /** `Build.VERSION.RELEASE`, verbatim. Never normalised here. */
  val osVersion: String? = null,
  /**
   * `allowed` or `denied` on this platform, as [PushPermission] spells them on
   * the wire. A string rather than the enum, for the reason [platform] and
   * [environment] are: the shared vectors carry the protocol's four states, two
   * of which are iOS's alone, and a replay has to be able to hold them.
   */
  val pushPermission: String? = null,
  val sdkVersion: String? = null,
  val optedIn: Boolean = true,
) {
  /**
   * The body of `POST /v1/devices`.
   *
   * Every field is present on every call, including the null ones, and that is
   * deliberate on both sides: the server reads an absent field as "unchanged"
   * and an explicit null as "erase". Except for pending tag changes, the SDK holds the state of
   * the device, so sending its snapshot stays
   * correct — `clearIdentity()` has to reach the server as a null, and it can
   * only do that if nulls are sent.
   */
  fun registrationBody(): Map<String, Any?> =
    mapOf(
      "token" to (token ?: ""),
      "platform" to platform,
      "environment" to environment,
      "external_id" to externalId,
      "tags" to tags.mapValues { it.value?.json },
      "timezone_id" to timezoneId,
      "locale" to locale,
      "app_version" to appVersion,
      "app_build" to appBuild,
      "bundle_id" to bundleId,
      "os_version" to osVersion,
      "push_permission" to pushPermission,
      "sdk_version" to sdkVersion,
      "opted_in" to optedIn,
    )

  /**
   * What "the server already knows this" means.
   *
   * Compared as the serialised body rather than field by field: the question is
   * whether another call would tell the server anything new, and the body is
   * exactly that question. A field added to the table later is covered without
   * anyone remembering to extend a comparison — which is why notifications
   * switched off in Settings, or a system update, is by itself a reason to
   * re-register: the body changes, so the fingerprint does.
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
          val tag = if (value == null) null else TagValue.of(value) ?: return@mapNotNull null

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
        appBuild = parsed["app_build"] as? String,
        bundleId = parsed["bundle_id"] as? String,
        osVersion = parsed["os_version"] as? String,
        pushPermission = parsed["push_permission"] as? String,
        sdkVersion = parsed["sdk_version"] as? String,
        optedIn = parsed["opted_in"] as? Boolean ?: true,
      )
    }
  }
}
