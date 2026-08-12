package dev.carillon.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * Registration is the call the whole product depends on: a device that never
 * registers receives nothing, and the failure is invisible from the app.
 */
class RegistrationTests {
  @Test
  fun sendsTheWholeTableAsTheServerDefinesIt() = runBlocking {
    val transport = FakeTransport()
    val engine = makeEngine(transport = transport)

    engine.refreshDeviceAttributes(
      "Europe/Paris",
      "fr-FR",
      "1.4.2",
      "4271",
      "com.example.app",
      "18.5",
    )
    engine.identify("user-42")
    engine.setTags(mapOf("plan" to tagOf("pro"), "seats" to tagOf(5), "beta" to tagOf(true)))
    engine.setToken(FCM_TOKEN)
    engine.settle()

    val body = transport.bodies.last()

    assertEquals("POST", transport.requests.last().method)
    assertEquals("/v1/devices", transport.requests.last().path)
    assertEquals(FCM_TOKEN, body["token"])
    assertEquals("android", body["platform"])
    // FCM has no sandbox. The field exists for iOS and Android declares the only
    // value it has.
    assertEquals("production", body["environment"])
    assertEquals("user-42", body["external_id"])
    assertEquals("Europe/Paris", body["timezone_id"])
    assertEquals("fr-FR", body["locale"])
    assertEquals("1.4.2", body["app_version"])
    assertEquals("4271", body["app_build"])
    assertEquals("com.example.app", body["bundle_id"])
    assertEquals("18.5", body["os_version"])
    assertEquals("allowed", body["push_permission"])
    assertEquals(Carillon.SDK_VERSION, body["sdk_version"])
    assertEquals(true, body["opted_in"])

    val tags = body["tags"] as Map<*, *>
    assertEquals("pro", tags["plan"])
    assertEquals(5.0, tags["seats"])
    assertEquals(true, tags["beta"])
  }

  @Test
  fun passesTheTokenThroughUnchanged() = runBlocking {
    // Unlike APNs, FCM hands over a string rather than bytes, so there is nothing
    // to encode. Google's own documentation asks for it verbatim, and folding its
    // case — which the iOS side does, because a hex token is case-insensitive —
    // would produce a token nobody issued.
    val transport = FakeTransport()
    val engine = makeEngine(transport = transport)
    val mixedCase = "AbC:APA91bXyZ_-0123456789"

    engine.setToken(mixedCase)
    engine.settle()

    assertEquals(mixedCase, transport.bodies.last()["token"])
  }

  @Test
  fun carriesTheKeyAsABearerToken() = runBlocking {
    val transport = FakeTransport()
    val engine = makeEngine(transport = transport, key = "carillon_mk_live_abc")

    engine.setToken(FCM_TOKEN)
    engine.settle()

    assertEquals("carillon_mk_live_abc", transport.requests.first().key)
  }

  @Test
  fun sendsNullRatherThanOmittingAClearedIdentity() = runBlocking {
    // The server reads an absent field as "unchanged" and an explicit null as
    // "erase". `clearIdentity()` can only mean the second, so the null has to be
    // on the wire — omitting it would leave the old identifier in place for ever
    // with nothing failing to say so.
    val transport = FakeTransport()
    val engine = makeEngine(transport = transport)

    engine.setToken(FCM_TOKEN)
    engine.identify("user-42")
    engine.settle()

    engine.identify(null)
    engine.settle()

    val body = transport.bodies.last()
    assertTrue(body.containsKey("external_id"))
    assertNull(body["external_id"])
  }

  @Test
  fun sendsNothingBeforeATokenExists() = runBlocking {
    // A registration without a token names no device. Everything set before
    // Firebase answers is held, and goes out with the first real call.
    val transport = FakeTransport()
    val engine = makeEngine(transport = transport)

    engine.identify("user-42")
    engine.setTags(mapOf("plan" to tagOf("pro")))
    engine.settle()

    assertTrue(transport.requests.isEmpty())

    engine.setToken(FCM_TOKEN)
    engine.settle()

    assertEquals(1, transport.requests.size)
    assertEquals("user-42", transport.bodies.last()["external_id"])
  }

  @Test
  fun sendsNothingBeforeAKeyExists() = runBlocking {
    // The bug a dirty flag causes, asserted: a token that arrives before the key
    // must not consume the reason to register, or the device never registers at
    // all and nothing anywhere fails.
    val transport = FakeTransport()
    val engine = makeEngine(transport = transport, key = "")

    engine.setToken(FCM_TOKEN)
    engine.settle()

    assertTrue(transport.requests.isEmpty())

    engine.configure("carillon_mk_live_abc", "https://api-staging.carillon.dev", false, null)
    engine.settle()

    assertEquals(1, transport.requests.size)
  }

  @Test
  fun coalescesSeveralChangesIntoTheLatestState() = runBlocking {
    val transport = FakeTransport()
    val engine = makeEngine(transport = transport)

    engine.setToken(FCM_TOKEN)
    engine.identify("first")
    engine.identify("second")
    engine.setTags(mapOf("plan" to tagOf("pro")))
    engine.settle()

    // Never two at once: both would upsert the same row, and the one that lands
    // second is whichever was slower — which is not the newer one.
    assertEquals(1, transport.peakConcurrency)
    assertTrue(transport.requests.size < 4)
    assertEquals("second", transport.bodies.last()["external_id"])
  }

  @Test
  fun holdsAChangeMadeMidFlightAndSendsItNext() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val transport = FakeTransport()
    transport.beforeSend = { index ->
      // Only the first call waits. The test opens the gate once it has made a
      // change behind it.
      if (index == 0) gate.await()
    }

    val engine = makeEngine(transport = transport)
    engine.setToken(FCM_TOKEN)

    // The first registration is now open. Anything set here has to reach the
    // server without racing the call already in flight.
    while (transport.requests.isEmpty()) yield()
    engine.identify("arrived-mid-flight")

    gate.complete(Unit)
    engine.settle()

    assertEquals(1, transport.peakConcurrency)
    assertEquals(2, transport.requests.size)
    assertNull(transport.bodies[0]["external_id"])
    assertEquals("arrived-mid-flight", transport.bodies[1]["external_id"])
  }

  @Test
  fun saysNothingTwiceWhenNothingChanged() = runBlocking {
    // A cold start with an unchanged device costs no call at all, which is what
    // makes "call register() on every launch" the cheap instruction the
    // documentation says it is.
    val transport = FakeTransport()
    val store = MemoryStore()
    val engine = makeEngine(transport = transport, store = store)

    engine.setToken(FCM_TOKEN)
    engine.settle()
    assertEquals(1, transport.requests.size)

    val second = makeEngine(transport = transport, store = store)
    second.setToken(FCM_TOKEN)
    second.settle()

    assertEquals(1, transport.requests.size)
  }

  @Test
  fun restoresItsStateAfterTheProcessIsKilled() = runBlocking {
    val store = MemoryStore()
    val first = makeEngine(store = store)

    first.identify("user-42")
    first.setTags(mapOf("plan" to tagOf("pro")))
    first.setToken(FCM_TOKEN)
    first.settle()

    // A fresh process, the same preferences. The token from the previous launch
    // is what lets an app that has been offline register the moment it starts.
    val restored = makeEngine(store = store)

    assertEquals("user-42", restored.currentState.externalId)
    assertEquals(FCM_TOKEN, restored.currentState.token)
    assertEquals(TagValue.Text("pro"), restored.currentState.tags["plan"])
  }

  @Test
  fun retriesWhenThereIsNoAnswerAndBacksOff() = runBlocking {
    val clock = FakeClock()
    val store = MemoryStore()
    val transport =
      FakeTransport(
        listOf(
          HttpOutcome.Failure("offline"),
          HttpOutcome.Failure("offline"),
          HttpOutcome.Response(503, ""),
          HttpOutcome.Response(200, """{"id":"01937b1e-0000-7000-8000-000000000001"}"""),
        )
      )

    val engine = makeEngine(transport = transport, clock = clock, store = store)
    engine.setToken(FCM_TOKEN)
    engine.settle()

    assertEquals(4, transport.requests.size)
    assertEquals(listOf(1_000L, 2_000L, 4_000L), clock.sleeps)
    assertEquals("01937b1e-0000-7000-8000-000000000001", store.deviceId)
  }

  @Test
  fun doesNotRetryAnythingElseInTheFourHundreds() = runBlocking {
    // A 422 retried forever is a bug loop rather than resilience: the token will
    // be just as malformed in five minutes.
    val clock = FakeClock()
    val transport =
      FakeTransport(
        listOf(
          HttpOutcome.Response(
            422,
            problemBody("invalid_request", 422, detail = "Send the FCM token as it was returned."),
          )
        )
      )

    val engine = makeEngine(transport = transport, clock = clock)
    engine.setToken("not a token")
    engine.settle()

    assertEquals(1, transport.requests.size)
    assertTrue(clock.sleeps.isEmpty())
    assertEquals(
      "invalid_request: Send the FCM token as it was returned.",
      engine.debugInfo().lastRegistrationResult,
    )
  }

  @Test
  fun triesAgainOnceTheRefusedBodyHasChanged() = runBlocking {
    // A refusal is about a payload, not about the device. A corrected identifier
    // deserves another attempt; the identical body does not.
    val transport = FakeTransport(listOf(HttpOutcome.Response(422, problemBody("x", 422))))
    val engine = makeEngine(transport = transport)

    engine.setToken(FCM_TOKEN)
    engine.settle()
    assertEquals(1, transport.requests.size)

    engine.identify("user-42")
    engine.settle()

    assertEquals(2, transport.requests.size)
  }

  @Test
  fun retriesA429BecauseTheAnswerIsToSlowDownRatherThanToStop() = runBlocking {
    val clock = FakeClock()
    val transport =
      FakeTransport(
        listOf(
          HttpOutcome.Response(429, problemBody("rate_limited", 429)),
          HttpOutcome.Response(200, "{}"),
        )
      )

    val engine = makeEngine(transport = transport, clock = clock)
    engine.setToken(FCM_TOKEN)
    engine.settle()

    assertEquals(2, transport.requests.size)
    assertEquals(listOf(1_000L), clock.sleeps)
  }

  @Test
  fun reportsNotificationsAsDeniedWhenTheSystemWouldShowNone() = runBlocking {
    // The seam answers `areNotificationsEnabled()` rather than the runtime
    // permission, and one question covers two cases because of it: an app whose
    // notifications were switched off in Settings has been granted everything it
    // ever asked for and will still show nothing. That is the case a customer
    // opens a ticket about, and the one this field exists to answer.
    val transport = FakeTransport()
    val engine = makeEngine(transport = transport, permissions = { false })

    engine.refreshPushPermission()
    engine.setToken(FCM_TOKEN)
    engine.settle()

    assertEquals("denied", transport.bodies.last()["push_permission"])
  }

  @Test
  fun registersAgainWhenTheNotificationPermissionChanges() = runBlocking {
    // The mechanism is the fingerprint, and it is the serialised body: a state
    // saying something new about the handset is by construction a state the
    // server has not been told. Nobody switches this from inside the app — it
    // changes in Settings, while the process is not running — so the next launch
    // discovering it is the only thing that carries it, and a launch that
    // discovers nothing still costs no call.
    val transport = FakeTransport()
    var enabled = true
    val engine = makeEngine(transport = transport, permissions = { enabled })

    engine.refreshPushPermission()
    engine.setToken(FCM_TOKEN)
    engine.settle()
    assertEquals(1, transport.requests.size)
    assertEquals("allowed", transport.bodies.last()["push_permission"])

    engine.refreshPushPermission()
    engine.settle()
    assertEquals(1, transport.requests.size)

    enabled = false
    engine.refreshPushPermission()
    engine.settle()

    assertEquals(2, transport.requests.size)
    assertEquals("denied", transport.bodies.last()["push_permission"])
  }

  @Test
  fun registersWithTheTokenFirebaseReturns() = runBlocking {
    // What `configure` does, and the whole of the new model: a token is asked
    // for, the device registers, and nobody is prompted. A base holding only
    // the people who were asked and said yes measures an app's onboarding
    // rather than its reach.
    val transport = FakeTransport()
    val prompt = FakePermissionRequest()
    val engine =
      makeEngine(transport = transport, tokenSource = { FCM_TOKEN }, permissions = { false })

    // The order `configure` uses: what the system can be asked is read first,
    // then the token is requested, so the first registration carries the truth
    // rather than a null it would have to correct a moment later.
    engine.refreshPushPermission()
    engine.acquireToken()
    engine.settle()

    assertEquals(FCM_TOKEN, transport.bodies.last()["token"])
    // The permission it really has travels with it: this handset would show
    // nothing today, and the row says so rather than not existing.
    assertEquals("denied", transport.bodies.last()["push_permission"])
    assertEquals(0, prompt.shown, "configuring the SDK must never show a dialogue")
  }

  @Test
  fun asksFirebaseOnlyOnceWhileOneRequestIsInFlight() = runBlocking {
    // configure() can be called again — a bench switching endpoints does it on
    // every Apply — and two token requests would race to write the same field.
    val transport = FakeTransport()
    val engine = makeEngine(transport = transport, tokenSource = { FCM_TOKEN })

    engine.acquireToken()
    engine.acquireToken()
    engine.settle()

    assertEquals(1, transport.requests.size)
  }

  @Test
  fun registersNothingWhenFirebaseCouldNotProduceAToken() = runBlocking {
    // Transient — no Play Services, or offline — and nothing a caller could act
    // on differently. It is a line in the log and in debugInfo(), and the token
    // may still arrive later through didRotate.
    val transport = FakeTransport()
    val engine = makeEngine(transport = transport, tokenSource = { null })

    engine.acquireToken()
    engine.settle()

    assertTrue(transport.requests.isEmpty())
    assertNotEquals("registered", engine.debugInfo().lastRegistrationResult)
  }

  @Test
  fun requestPermissionShowsTheDialogueAndAnswersWithWhatItDecided() = runBlocking {
    // The person says yes: the fake flips what the system reports, exactly as
    // granting the permission would.
    var enabled = false
    val transport = FakeTransport()
    val prompt = FakePermissionRequest { enabled = true }
    val engine = makeEngine(transport = transport, permissions = { enabled })

    engine.setToken(FCM_TOKEN)
    engine.settle()

    assertEquals(PushPermission.ALLOWED, engine.requestPermission(prompt))
    engine.settle()

    assertEquals(1, prompt.shown)
    // No call of its own: the permission is part of the state, so it is part of
    // the fingerprint, so the registration loop is what carries it.
    assertEquals("allowed", transport.bodies.last()["push_permission"])
  }

  @Test
  fun requestPermissionAnswersDeniedWhenNothingChanged() = runBlocking {
    // A refusal, and a dismissal, and a system that showed nothing at all: from
    // here they are the same answer, because the question is only ever whether
    // a notification would be displayed.
    val prompt = FakePermissionRequest()
    val engine = makeEngine(permissions = { false })

    assertEquals(PushPermission.DENIED, engine.requestPermission(prompt))

    assertEquals(1, prompt.shown)
  }

  @Test
  fun requestPermissionShowsNothingWhenThereIsNothingToAsk() = runBlocking {
    // Notifications are already enabled — below API 33 they need no permission
    // at all, and above it this app has been granted one. A dialogue exists to
    // change an answer, not to confirm one.
    val prompt = FakePermissionRequest()
    val engine = makeEngine(permissions = { true })

    assertEquals(PushPermission.ALLOWED, engine.requestPermission(prompt))

    assertEquals(0, prompt.shown)
  }
}
