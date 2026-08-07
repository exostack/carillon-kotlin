package dev.carillon.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Opens are the half of the product the customer cannot fake, and the half the
 * SDK can silently lose. Every test here is about not losing one.
 */
class EventTests {
  private val deliveryId = "01937b1e-0000-7000-8000-0000000000ff"

  /**
   * What FCM hands over. `data` is typed as a map of strings, so the server
   * serialises the reserved object into one — and on a tap of a system-displayed
   * notification these same entries arrive as intent extras, unchanged.
   */
  private fun payload(id: String) =
    mapOf(
      "order_id" to "42",
      "carillon" to """{"delivery_id":"$id"}""",
    )

  @Test
  fun queuesAndReportsAnOpen() = runBlocking {
    val transport = FakeTransport()
    val clock = FakeClock()
    val engine = makeEngine(transport = transport, clock = clock)

    assertTrue(engine.didOpen(payload(deliveryId)))
    engine.settle()

    assertEquals("POST", transport.requests.last().method)
    assertEquals("/v1/events", transport.requests.last().path)

    val events = transport.bodies.last()["events"] as List<*>
    val first = events.first() as Map<*, *>
    assertEquals(1, events.size)
    assertEquals("opened", first["type"])
    assertEquals(deliveryId, first["delivery_id"])
    assertEquals(Iso8601.format(clock.nowMs()), first["at"])
  }

  @Test
  fun ignoresANotificationThatIsNotOurs() = runBlocking {
    // Another SDK's notification, or one the app raised itself. There is no
    // delivery to report an open against, and possession of a delivery id is the
    // only thing that proves a notification arrived at all. Silence here is what
    // lets the app forward every intent without sorting them first.
    val transport = FakeTransport()
    val engine = makeEngine(transport = transport)

    assertFalse(engine.didOpen(mapOf("title" to "Hello")))
    assertFalse(engine.didOpen(mapOf("carillon" to """{"something_else":"x"}""")))
    assertFalse(engine.didOpen(mapOf("carillon" to "not json at all")))
    engine.settle()

    assertTrue(transport.requests.isEmpty())
    assertEquals(0, engine.debugInfo().queuedEvents)
  }

  @Test
  fun keepsAnEventUntilTheServerTakesIt() = runBlocking {
    // At-least-once, by design. The server is idempotent per delivery and event
    // type, so a replay after a dropped connection changes nothing — which is
    // what makes it safe to keep the event until a 2xx has actually been seen
    // rather than dropping it the moment it went to the network.
    val clock = FakeClock()
    val store = MemoryStore()
    val transport =
      FakeTransport(
        listOf(
          HttpOutcome.Failure("offline"),
          HttpOutcome.Response(503, ""),
          HttpOutcome.Response(202, """{"received":1}"""),
        )
      )

    val engine = makeEngine(transport = transport, clock = clock, store = store)
    engine.didOpen(payload(deliveryId))
    engine.settle()

    assertEquals(3, transport.requests.size)
    assertEquals(listOf(1_000L, 2_000L), clock.sleeps)
    assertTrue(store.events.isEmpty())
  }

  @Test
  fun survivesTheProcessBeingKilledBeforeItCouldReport() = runBlocking {
    val store = MemoryStore()
    val first = makeEngine(store = store, key = "")

    first.didOpen(payload(deliveryId))
    first.settle()

    assertEquals(1, store.events.size)

    // A new process, the same preferences: the open is still owed and goes out.
    val transport = FakeTransport()
    val restored = makeEngine(transport = transport, store = store)
    restored.startEventLoop()
    restored.settle()

    val events = transport.bodies.last()["events"] as List<*>
    assertEquals(deliveryId, (events.first() as Map<*, *>)["delivery_id"])
    assertTrue(store.events.isEmpty())
  }

  @Test
  fun sendsAtMostAHundredAtATime() = runBlocking {
    val transport = FakeTransport()
    val store = MemoryStore()
    // Queued with no key, so the whole backlog exists before anything is sent —
    // which is exactly the situation the cap is for: an app that has been
    // offline, or opened before it was configured.
    val engine = makeEngine(transport = transport, store = store, key = "")

    repeat(250) { index ->
      engine.didOpen(payload(String.format("01937b1e-0000-7000-8000-%012d", index)))
    }
    engine.settle()
    assertEquals(250, store.events.size)

    engine.configure(TEST_KEY, "https://api-staging.carillon.dev", false, null)
    engine.settle()

    val batches = transport.bodies.map { (it["events"] as List<*>).size }
    assertEquals(listOf(100, 100, 50), batches)
    assertTrue(store.events.isEmpty())
  }

  @Test
  fun dropsABatchTheServerWillNeverAcceptRatherThanLoopingOnIt() = runBlocking {
    // The queue would otherwise grow for the life of the install, retrying a body
    // that is just as invalid every time. The reason is logged instead.
    val clock = FakeClock()
    val store = MemoryStore()
    val transport =
      FakeTransport(listOf(HttpOutcome.Response(400, problemBody("invalid_request", 400))))

    val engine = makeEngine(transport = transport, clock = clock, store = store)
    engine.didOpen(payload(deliveryId))
    engine.settle()

    assertEquals(1, transport.requests.size)
    assertTrue(clock.sleeps.isEmpty())
    assertTrue(store.events.isEmpty())
  }

  @Test
  fun handsAColdStartOpenToTheFirstSubscriber() = runBlocking {
    // An app launched by a tap runs its whole startup before anything attaches a
    // handler, and that tap is the journey the notification was sent to start.
    val engine = makeEngine()
    engine.didOpen(payload(deliveryId))

    val seen = ArrayList<String>()
    engine.setOnOpened { seen.add(it.deliveryId) }

    assertEquals(listOf(deliveryId), seen)

    // And once someone is listening, opens arrive as they happen rather than
    // being held a second time.
    engine.didOpen(payload("01937b1e-0000-7000-8000-000000000002"))
    assertEquals(2, seen.size)

    engine.settle()
  }

  @Test
  fun handsTheWholePayloadOverBecauseTheDestinationIsTheCustomersOwn() = runBlocking {
    val engine = makeEngine()
    var received: OpenedNotification? = null
    engine.setOnOpened { received = it }

    engine.didOpen(payload(deliveryId))
    engine.settle()

    assertEquals("42", received?.data?.get("order_id"))
  }

  @Test
  fun carriesAnOpenQueuedBeforeConfigureIntoTheRealStore() = runBlocking {
    // The cold start that matters: an app that forwards its launcher intent from
    // an activity rather than configuring in `Application.onCreate` queues the
    // tap before there is anywhere durable to put it. Building a second engine at
    // `configure` would drop it, and nothing would fail to say so.
    val transport = FakeTransport()
    val engine = makeEngine(transport = transport, key = "")

    engine.didOpen(payload(deliveryId))
    engine.settle()

    val real = MemoryStore()
    engine.adopt(real, Permissions { true })
    engine.configure(TEST_KEY, "https://api-staging.carillon.dev", false, null)
    engine.settle()

    val events = transport.bodies.last()["events"] as List<*>
    assertEquals(deliveryId, (events.first() as Map<*, *>)["delivery_id"])
    assertTrue(real.events.isEmpty())
  }

  @Test
  fun keepsWhatWasAlreadyOwedWhenItTakesOnTheRealStore() = runBlocking {
    // Both directions: an open from a previous launch is still owed, and one
    // queued this launch before configure is too. Merged rather than replaced.
    val previous =
      MemoryStore().apply {
        events = listOf(QueuedEvent(QueuedEvent.OPENED, "from-a-previous-launch", 1_770_000_000_000))
      }

    val transport = FakeTransport()
    val engine = makeEngine(transport = transport, key = "")
    engine.didOpen(payload(deliveryId))
    engine.settle()

    engine.adopt(previous, Permissions { true })

    assertEquals(2, previous.events.size)
    assertEquals(
      setOf("from-a-previous-launch", deliveryId),
      previous.events.map { it.deliveryId }.toSet(),
    )
  }

  @Test
  fun queuesNothingForAMessageThatWasMerelyReceived() = runBlocking {
    // `received` needs a Notification Service Extension on iOS and is a later,
    // additive decision for the protocol as a whole. Reporting one from Android
    // alone would produce an event type the API refuses, and a figure that means
    // one thing on one platform and nothing on the other.
    val transport = FakeTransport()
    val engine = makeEngine(transport = transport)

    engine.didReceive(payload(deliveryId))
    engine.settle()

    assertEquals(0, engine.debugInfo().queuedEvents)
    assertTrue(transport.requests.isEmpty())
  }
}
