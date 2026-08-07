package dev.carillon.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shared vectors, replayed.
 *
 * `src/test/resources/registration.json` and `events.json` are copied **verbatim**
 * from `carillon-swift/Tests/ConformanceFixtures`. They are not this SDK's
 * fixtures and must never be edited here: they are the contract between every
 * Carillon SDK, and changing one is a cross-SDK decision that starts on the
 * server side and lands in every language at once. If a case here fails, either
 * this SDK is wrong or the decision has been taken and the files are being
 * updated together — never one of them alone.
 *
 * Each case is a state expressed in the API's own field names and the exact
 * request it must produce. Bodies are compared as parsed trees rather than as
 * text: key order is a property of whichever map the language happens to use,
 * and it is not part of the contract.
 */
class ConformanceTests {
  private fun fixture(name: String): Map<*, *> {
    val stream = javaClass.classLoader.getResourceAsStream(name)
    assertNotNull(stream, "$name is missing from the test resources")

    val parsed = Json.parse(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    assertNotNull(parsed, "$name is not readable as JSON")

    return parsed as Map<*, *>
  }

  private fun cases(document: Map<*, *>): List<Map<*, *>> =
    (document["cases"] as List<*>).map { it as Map<*, *> }

  @Test
  fun registrationVectors() {
    val document = fixture("registration.json")
    val cases = cases(document)

    assertTrue(cases.isNotEmpty(), "the vectors carry no cases")

    cases.forEach { case ->
      val name = case["name"]
      val given = case["given"] as Map<*, *>
      val expect = case["expect"] as Map<*, *>
      val expected = (expect["body"] as Map<*, *>).toMutableMap()

      assertEquals("POST", expect["method"], "$name: method")
      assertEquals("/v1/devices", expect["path"], "$name: path")

      val produced = normalise(state(given).registrationBody()).toMutableMap()

      // `platform` is the one field that is per-SDK by construction, and the
      // vectors were captured on iOS. Asserted on both sides rather than skipped,
      // so the divergence is stated instead of hidden — and so a Kotlin build
      // that started announcing itself as iOS would fail here.
      assertEquals("ios", expected.remove("platform"), "$name: the vectors come from iOS")
      assertEquals("android", produced.remove("platform"), "$name: this SDK speaks for Android")

      assertEquals(expected, produced, "$name: body")
    }
  }

  @Test
  fun eventVectors() {
    val document = fixture("events.json")
    val cases = cases(document)

    assertEquals(
      Engine.MAX_EVENTS_PER_BATCH.toDouble(),
      document["batch_limit"],
      "the batch cap is the API's, and both SDKs read it from here",
    )
    assertTrue(cases.isNotEmpty(), "the vectors carry no cases")

    cases.forEach { case ->
      val name = case["name"]
      val given = case["given"] as Map<*, *>
      val expect = case["expect"] as Map<*, *>

      assertEquals("POST", expect["method"], "$name: method")
      assertEquals("/v1/events", expect["path"], "$name: path")

      val queue = (given["queue"] as List<*>).map { event(it as Map<*, *>) }
      val batch = queue.take(Engine.MAX_EVENTS_PER_BATCH)
      val produced = normalise(mapOf("events" to batch.map { it.json() }))

      assertEquals(expect["body"], produced, "$name: body")
      assertEquals(
        expect["remaining_after_success"],
        (queue.size - batch.size).toDouble(),
        "$name: what stays queued for the next request",
      )
    }
  }

  /**
   * The replay harness: a state in the API's field names, rebuilt into this SDK's
   * own type.
   *
   * `environment` is read from the case rather than hard-coded, for the same
   * reason `platform` is a field: the vector exercises the mapping, and a
   * separate test asserts that this SDK's own engine only ever produces
   * `production`, which is the only value FCM has.
   */
  private fun state(given: Map<*, *>): DeviceState =
    DeviceState(
      token = given["token"] as? String,
      platform = DeviceState.ANDROID,
      environment = given["environment"] as? String ?: DeviceState.PRODUCTION,
      externalId = given["external_id"] as? String,
      tags =
        (given["tags"] as? Map<*, *> ?: emptyMap<Any?, Any?>())
          .mapNotNull { (name, value) ->
            TagValue.of(value)?.let { name.toString() to it }
          }
          .toMap(),
      timezoneId = given["timezone_id"] as? String,
      locale = given["locale"] as? String,
      appVersion = given["app_version"] as? String,
      sdkVersion = given["sdk_version"] as? String,
      optedIn = given["opted_in"] as? Boolean ?: true,
    )

  private fun event(raw: Map<*, *>): QueuedEvent =
    QueuedEvent(
      type = raw["type"] as? String ?: QueuedEvent.OPENED,
      deliveryId = raw["delivery_id"] as? String ?: "",
      atMs = Iso8601.parse(raw["at"] as? String ?: "") ?: 0,
    )

  /**
   * Through the encoder and back, so both sides of every comparison are the same
   * kind of tree.
   *
   * A body built in Kotlin holds `Int` and `TagValue`; one parsed from the
   * fixture holds `Double` and `String`. Comparing them directly would fail on
   * the types rather than on the values, which is the opposite of what these
   * vectors are for.
   */
  private fun normalise(body: Map<String, Any?>): Map<*, *> = Json.parse(Json.encode(body)) as Map<*, *>
}
