package dev.carillon.sdk

import kotlinx.coroutines.yield

/**
 * The network, scripted.
 *
 * Records every request in order, so a test can state the exact call the SDK made
 * rather than that it made one — the request body is the contract with the
 * server, and it is the thing worth being precise about.
 */
internal class FakeTransport(private val scripted: List<HttpOutcome> = emptyList()) : Transport {
  private val lock = Any()
  private val recorded = ArrayList<HttpRequest>()
  private var inFlight = 0
  private var peak = 0

  /**
   * Awaited before each send. A test uses it to hold a call open and prove that
   * nothing else starts while it is.
   */
  var beforeSend: (suspend (Int) -> Unit)? = null

  /**
   * Returned once the script runs out. A device that keeps trying is the normal
   * state of this SDK, so the fallback has to be an answer rather than a crash.
   */
  var fallback: HttpOutcome = HttpOutcome.Response(200, "{}")

  val requests: List<HttpRequest>
    get() = synchronized(lock) { ArrayList(recorded) }

  val bodies: List<Map<*, *>>
    get() = requests.map { Json.parse(it.body) as? Map<*, *> ?: emptyMap<Any?, Any?>() }

  /**
   * The highest number of calls that were ever open at once. One is the whole
   * point of the registration loop.
   */
  val peakConcurrency: Int
    get() = synchronized(lock) { peak }

  override suspend fun send(request: HttpRequest): HttpOutcome {
    val index =
      synchronized(lock) {
        recorded.add(request)
        inFlight += 1
        peak = maxOf(peak, inFlight)

        recorded.size - 1
      }

    beforeSend?.invoke(index)

    // Yielded a few times so that a second loop, if one existed, would have room
    // to start and be seen. Without this the peak above could read one purely
    // because nothing ever got the chance to overlap.
    repeat(4) { yield() }

    return synchronized(lock) {
      inFlight -= 1

      if (index < scripted.size) scripted[index] else fallback
    }
  }
}

/**
 * Time, on demand.
 *
 * A backoff schedule that can only be verified by waiting five minutes is a
 * schedule nobody verifies. Sleeping advances the clock instead, so an event
 * recorded after a retry carries the instant it would really have carried.
 */
internal class FakeClock(start: Long = 1_770_000_000_000) : Clock {
  private val lock = Any()
  private var current = start
  private val recorded = ArrayList<Long>()

  val sleeps: List<Long>
    get() = synchronized(lock) { ArrayList(recorded) }

  override fun nowMs(): Long = synchronized(lock) { current }

  override suspend fun sleep(milliseconds: Long) {
    synchronized(lock) {
      recorded.add(milliseconds)
      current += milliseconds
    }
  }
}

/**
 * The system's permission dialogue, counted rather than shown.
 *
 * Most of what this exists to prove is a negative: configuring the SDK
 * registers the device and asks nobody for anything, and a count is the only
 * way to state that as an assertion. `onShow` is how a test says what the
 * person answered — a dialogue's effect is on what the system reports
 * afterwards, never on a value it hands back.
 */
internal class FakePermissionRequest(private val onShow: () -> Unit = {}) : PermissionRequest {
  var shown = 0
    private set

  override suspend fun show() {
    shown += 1
    onShow()
  }
}

/** An RFC 9457 body, as the API produces them. */
internal fun problemBody(
  code: String,
  status: Int,
  title: String = "Something to fix",
  detail: String? = null,
): String =
  Json.encode(
    buildMap {
      put("type", "https://carillon.dev/errors/$code")
      put("title", title)
      put("status", status)
      put("code", code)
      put("docs", "https://carillon.dev/docs/errors#$code")
      if (detail != null) put("detail", detail)
    }
  )

internal const val TEST_KEY = "carillon_mk_test_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

/** A real FCM token is opaque; this is one of the right shape and length. */
internal const val FCM_TOKEN =
  "cXy7Z9abcdefghij:APA91bHq0000000000000000000000000000000000000000000000000000"

/**
 * An engine wired to doubles, with the store and clock handed back so a test can
 * look at what survived.
 */
internal fun makeEngine(
  transport: Transport = FakeTransport(),
  clock: Clock = FakeClock(),
  store: Store = MemoryStore(),
  key: String = TEST_KEY,
  tokenSource: TokenSource = TokenSource { FCM_TOKEN },
  permissions: Permissions = Permissions { true },
  debugEnabled: Boolean = false,
): Engine =
  Engine(
    store = store,
    transport = transport,
    clock = clock,
    tokenSource = tokenSource,
    permissions = permissions,
    key = key,
    endpoint = "https://api-staging.carillon.dev",
    debugEnabled = debugEnabled,
  )
