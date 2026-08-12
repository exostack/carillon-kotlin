package dev.carillon.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BackoffTests {
  @Test
  fun doublesFromOneSecond() {
    assertEquals(
      listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L),
      (1..6).map(Backoff::delayMs),
    )
  }

  @Test
  fun waitsForNothingBeforeTheFirstAttempt() {
    assertEquals(0L, Backoff.delayMs(0))
  }

  @Test
  fun stopsAtFiveMinutes() {
    // The ceiling matters more than the curve: a device that has been offline all
    // day has to register within a minute of coming back, and an SDK that has
    // backed off to an hour is indistinguishable from one that is broken.
    assertEquals(300_000L, Backoff.delayMs(20))
    assertEquals(300_000L, Backoff.delayMs(1_000))
  }
}

class JsonTests {
  @Test
  fun sortsKeysSoTheSameStateSerialisesTheSameWayTwice() {
    // The fingerprint is a serialised body. A map that serialised in a different
    // order on the next launch would make every cold start look like a change and
    // re-register the whole park.
    assertEquals(
      """{"a":1,"b":2,"c":3}""",
      Json.encode(linkedMapOf("c" to 3, "a" to 1, "b" to 2)),
    )
  }

  @Test
  fun keepsAWholeNumberWhole() {
    // A tag that went in as 5 and arrives as 5.0 is an audience filter the
    // customer no longer recognises.
    assertEquals("""{"ratio":1.5,"seats":5}""", Json.encode(mapOf("seats" to 5, "ratio" to 1.5)))
  }

  @Test
  fun writesNullsRatherThanOmittingThem() {
    assertEquals("""{"external_id":null}""", Json.encode(mapOf("external_id" to null)))
  }

  @Test
  fun escapesWhatWouldOtherwiseBreakTheDocument() {
    assertEquals(""""a\"b\\c\nd"""", Json.encode("a\"b\\c\nd"))
  }

  @Test
  fun readsBackWhatItWrote() {
    val value =
      mapOf(
        "text" to "hé\"llo",
        "flag" to true,
        "nothing" to null,
        "list" to listOf(1, 2.5, "three"),
        "nested" to mapOf("a" to 1),
      )
    val parsed = Json.parse(Json.encode(value)) as Map<*, *>

    assertEquals("hé\"llo", parsed["text"])
    assertEquals(true, parsed["flag"])
    assertNull(parsed["nothing"])
    assertEquals(listOf(1.0, 2.5, "three"), parsed["list"])
    assertEquals(mapOf("a" to 1.0), parsed["nested"])
  }

  @Test
  fun answersNullToAnythingMalformedRatherThanThrowing() {
    // Every caller is reading something it did not write — a problem document
    // from an edge that answered with HTML, a payload assembled by a server it
    // cannot see — and none has a better answer than to carry on without it.
    listOf("", "{", "{\"a\"}", "not json", "[1,", "{\"a\":}").forEach {
      assertNull(Json.parse(it), "should not parse: $it")
    }
  }
}

class DebugInfoTests {
  @Test
  fun answersEveryQuestionSupportAsksFirst() = runBlocking {
    val store = MemoryStore()
    val clock = FakeClock()
    val transport =
      FakeTransport(
        listOf(HttpOutcome.Response(200, """{"id":"01937b1e-0000-7000-8000-000000000001"}"""))
      )

    val engine =
      makeEngine(transport = transport, clock = clock, store = store, key = "carillon_mk_test_abc")

    engine.refreshDeviceAttributes(
      "Europe/Paris",
      "fr-FR",
      "1.4.2",
      "4271",
      "com.example.app",
      "18.5",
    )
    engine.setToken(FCM_TOKEN)
    engine.settle()

    engine.didOpen(mapOf("carillon" to """{"delivery_id":"01937b1e-0000-7000-8000-0000000000ff"}"""))

    val info = engine.debugInfo()

    assertEquals(Carillon.SDK_VERSION, info.sdkVersion)
    // Whole, not truncated: a mobile key ships inside every copy of the app, so
    // anyone with the APK already has it, and hiding it here would only cost the
    // support engineer the one identifier that says which app this is.
    assertEquals("carillon_mk_test_abc", info.key)
    assertEquals("https://api-staging.carillon.dev", info.endpoint)
    assertEquals(FCM_TOKEN, info.token)
    assertEquals("01937b1e-0000-7000-8000-000000000001", info.deviceId)
    assertEquals("production", info.environment)
    assertEquals("com.example.app", info.bundleId)
    assertEquals("4271", info.appBuild)
    assertEquals("18.5", info.osVersion)
    // The first thing to look at when an integration is right and nothing shows.
    assertEquals("allowed", info.pushPermission)
    assertEquals(clock.nowMs(), info.lastRegistrationAtMs)
    assertEquals("registered", info.lastRegistrationResult)
    assertEquals(1, info.queuedEvents)

    engine.settle()
  }

  @Test
  fun answersBeforeAnythingHasHappened() {
    // Support asks for this when nothing works. It must never be the call that
    // also fails.
    val info = makeEngine(key = "").debugInfo()

    assertEquals("", info.key)
    assertNull(info.token)
    assertNull(info.deviceId)
    assertNull(info.lastRegistrationAtMs)
    assertEquals(0, info.queuedEvents)
  }

  @Test
  fun rendersAsSomethingWorthPastingIntoATicket() {
    val rendered = makeEngine(key = "carillon_mk_test_abc").debugInfo().toString()

    assertTrue(rendered.contains("sdk_version"))
    assertTrue(rendered.contains("carillon_mk_test_abc"))
    // Absent values read as absent rather than as the word "null".
    assertTrue(rendered.contains("—"))
    assertFalse(rendered.contains("null"))
  }

  @Test
  fun serialisesUnderTheNamesTheApiUses() {
    val parsed = Json.parse(makeEngine(key = "k").debugInfo().toJson()) as Map<*, *>

    assertEquals(Carillon.SDK_VERSION, parsed["sdk_version"])
    assertEquals(0.0, parsed["queued_events"])
  }
}

class DebugLoggingTests {
  @Test
  fun saysNothingWhenTheFlagIsOff() {
    val lines = ArrayList<String>()
    Log.onLine = { lines.add(it) }

    try {
      Log.write(false) { "should not appear" }

      assertTrue(lines.isEmpty())
    } finally {
      Log.onLine = null
    }
  }

  @Test
  fun mirrorsEveryLineToTheBenchWhenItIsOn() {
    val lines = ArrayList<String>()
    Log.onLine = { lines.add(it) }

    try {
      Log.write(true) { "registering" }

      assertEquals(listOf("registering"), lines)
    } finally {
      Log.onLine = null
    }
  }
}

class StoreTests {
  @Test
  fun roundTripsTheWholeStateThroughTheStore() {
    val store = MemoryStore()
    val state =
      DeviceState(
        token = FCM_TOKEN,
        externalId = "user-42",
        tags =
          mapOf(
            "plan" to tagOf("pro"),
            "seats" to tagOf(5),
            "beta" to tagOf(true),
            "ratio" to tagOf(1.5),
          ),
        timezoneId = "Europe/Paris",
        locale = "fr-FR",
        appVersion = "1.4.2",
        sdkVersion = Carillon.SDK_VERSION,
      )

    store.state = state
    assertEquals(state, store.state)
  }

  @Test
  fun keepsABooleanTagABoolean() {
    // A tag that went in as `true` and comes back as `1` is an audience filter
    // that silently stops matching.
    val restored = DeviceState.restore(DeviceState(tags = mapOf("beta" to tagOf(true))).stored())

    assertEquals(TagValue.Flag(true), restored?.tags?.get("beta"))
  }

  @Test
  fun keepsAWholeTagWhole() {
    val restored = DeviceState.restore(DeviceState(tags = mapOf("seats" to tagOf(5))).stored())

    assertEquals(TagValue.Whole(5), restored?.tags?.get("seats"))
  }

  @Test
  fun remembersATokenThatWasNeverObtained() {
    // The nullable token is the difference between the stored state and the wire
    // body, where it is a string. Losing the distinction would make a device that
    // has no token look like one whose token is the empty string.
    assertNull(DeviceState.restore(DeviceState().stored())?.token)
  }

  @Test
  fun dropsAValueItCannotRead() {
    // Written by an older or newer install. The state rebuilds itself on the next
    // registration, and a queue that cannot be read could never be sent either.
    assertNull(DeviceState.restore("not json"))
  }

  @Test
  fun fingerprintIgnoresTheOrderAMapHappensToHave() {
    val one = DeviceState(token = "ab", tags = linkedMapOf("a" to tagOf(1), "b" to tagOf(2)))
    val two = DeviceState(token = "ab", tags = linkedMapOf("b" to tagOf(2), "a" to tagOf(1)))

    assertEquals(one.fingerprint(), two.fingerprint())
  }

  @Test
  fun fingerprintChangesWhenAnythingTheServerWouldSeeChanges() {
    val state = DeviceState(token = "ab", externalId = "user-42")

    assertNotEquals(state.fingerprint(), state.copy(optedIn = false).fingerprint())
  }

  @Test
  fun roundTripsAQueuedEventThroughTheStore() {
    val store = MemoryStore()
    val event = QueuedEvent(QueuedEvent.OPENED, "01937b1e-0000-7000-8000-0000000000ff", 1_770_000_000_000)

    store.events = listOf(event)

    assertEquals(listOf(event), store.events)
  }

  @Test
  fun formatsInstantsInUtcWithMilliseconds() {
    // The API validates ISO 8601, and a device in Paris must not report an open
    // in local time with an offset the server then has to guess the intent of.
    assertEquals("2026-02-02T02:40:00.000Z", Iso8601.format(1_770_000_000_000))
    assertEquals(1_770_000_000_000, Iso8601.parse("2026-02-02T02:40:00.000Z"))
  }
}
