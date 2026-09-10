package dev.carillon.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Everything the SDK actually does, with no Android in it.
 *
 * The facade is an object because that is the shape a customer wants to call;
 * this is a class because that is the shape a test wants to hold. The split is
 * the whole reason the protocol logic here can be exercised without a device, a
 * network, or a wall clock — and it is why this file and the Swift `Engine`
 * describe the same behaviour rather than two behaviours that resemble one
 * another.
 */
internal class Engine(
  store: Store,
  private var transport: Transport,
  private val clock: Clock,
  private val tokenSource: TokenSource,
  permissions: Permissions,
  key: String = "",
  endpoint: String = Carillon.DEFAULT_ENDPOINT,
  debugEnabled: Boolean = false,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
  /**
   * Every field below is read and written inside this monitor, and never across
   * a suspension point. A lock held across one blocks a thread the dispatcher
   * needs; the discipline is the same one the iOS side enforces through the
   * shape of its `Lock`.
   */
  private val lock = Any()

  private var store: Store = store
  private var permissions: Permissions = permissions
  private var key: String = key
  private var endpoint: String = endpoint
  private var debugEnabled: Boolean = debugEnabled

  private var state: DeviceState
  private var registrationJob: Job? = null
  private var eventJob: Job? = null
  private var tokenJob: Job? = null
  private var lastRegistrationAtMs: Long? = null
  private var lastRegistrationResult: String? = null

  /**
   * The body the server refused. Kept so the identical one is not sent again,
   * while a changed one still is — a refusal is about a payload, not about the
   * device, and a corrected identifier deserves another attempt.
   */
  private var refusedFingerprint: String? = null

  /**
   * Opens that arrived before anyone was listening.
   *
   * An app launched by a tap runs its whole startup before a handler is
   * attached, and that tap is the most valuable one there is — it is the journey
   * the notification was sent to start. Held until the first subscriber attaches
   * rather than delivered into the void.
   */
  private val heldOpens = ArrayList<OpenedNotification>()
  private var onOpened: ((OpenedNotification) -> Unit)? = null

  private fun ensureInstallationSecret(store: Store) {
    if (store.installationSecret != null) return
    val random = java.security.SecureRandom()
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    // Base64url of 256 random bits, without requiring Android API 26's Base64.
    store.installationSecret = buildString {
      repeat(42) { append(alphabet[random.nextInt(64)]) }
      append(alphabet[random.nextInt(4) * 16])
    }
    store.registeredFingerprint = null
  }

  init {
    ensureInstallationSecret(store)
    // The stored state, or a fresh one. Restoring first matters: a token obtained
    // on a previous launch is what lets an app that has been offline register the
    // moment it starts.
    state =
      (store.state ?: DeviceState()).copy(
        platform = DeviceState.ANDROID,
        environment = DeviceState.PRODUCTION,
        sdkVersion = Carillon.SDK_VERSION,
      )
  }

  // Configuration ------------------------------------------------------------

  /**
   * Takes on the real storage, once a context has arrived.
   *
   * There is one engine for the life of the process, exactly as on iOS, because
   * a callback can land before `configure` does: an app that forwards its
   * launcher intent from an activity rather than configuring in
   * `Application.onCreate` queues its most valuable open — a cold start from a
   * tap — into whatever store existed at the time. Building a second engine here
   * would drop it on the floor, and nothing would fail to say so.
   *
   * Events are merged rather than replaced, so nothing owed to the server is
   * lost in either direction. The persisted state wins where there is one: it
   * carries the token from the previous launch, which the in-memory state cannot
   * have.
   */
  fun adopt(store: Store, permissions: Permissions) {
    synchronized(lock) {
      val owed = this.store.events
      ensureInstallationSecret(store)
      this.store = store
      this.permissions = permissions

      if (owed.isNotEmpty()) store.events = store.events + owed

      val persisted = store.state

      state =
        (persisted ?: state).copy(
          platform = DeviceState.ANDROID,
          environment = DeviceState.PRODUCTION,
          sdkVersion = Carillon.SDK_VERSION,
        )

      if (persisted == null) store.state = state
    }
  }

  fun configure(key: String, endpoint: String, debug: Boolean, transport: Transport?) {
    synchronized(lock) {
      this.key = key
      this.endpoint = endpoint
      this.debugEnabled = debug
      if (transport != null) this.transport = transport
    }

    Log.write(debug) { "configured for $endpoint" }

    // A key arriving is itself news: an event queued before `configure` — a cold
    // start from a tap — has been waiting for one.
    startRegistrationLoop()
    startEventLoop()
  }

  // Registration -------------------------------------------------------------

  /**
   * Asks Firebase for a token, and asks nobody for anything.
   *
   * **A push token is transport addressing, not consent.** FCM issues one with
   * no permission granted and no dialogue shown — a permission gates whether
   * anything is *displayed* — so the device registers from its first launch,
   * carrying the permission it really has. A base holding only the people who
   * were asked and said yes measures an app's onboarding rather than its reach.
   *
   * A token Firebase could not produce is a line in the log and in
   * `debugInfo()`: it is transient — no Play Services, or offline — and nothing
   * a caller could act on differently.
   */
  fun acquireToken() {
    synchronized(lock) {
      if (tokenJob != null) return

      tokenJob =
        scope.launch {
          val token = tokenSource.currentToken()

          if (token == null) {
            Log.write(isDebugEnabled) { "no token from Firebase" }
            record("no token from Firebase")
          } else {
            setToken(token)
          }

          synchronized(lock) { tokenJob = null }
        }
    }
  }

  /**
   * Shows the system's permission dialogue, and answers with what it decided.
   *
   * One question, one answer. Registration is not part of it — that happened at
   * `configure` — and the new permission needs no call of its own either: it is
   * part of the state, so it is part of the fingerprint, so the registration
   * loop sends it as a matter of course.
   *
   * Nothing is asked when the system would already show a notification: a
   * dialogue exists to change an answer, not to confirm one.
   */
  suspend fun requestPermission(request: PermissionRequest): PushPermission {
    if (!permissions.notificationsEnabled()) request.show()

    refreshPushPermission()

    return currentPermission()
  }

  // The state the app sets ---------------------------------------------------

  fun setToken(token: String) = mutate { it.copy(token = token) }

  fun identify(externalId: String?) = mutate { it.copy(externalId = externalId) }

  /**
   * Replaced whole, never merged. The SDK holds the canonical map and the server
   * replaces what it holds — a merge here would make removing a tag impossible
   * without inventing a word for "remove".
   */
  fun setTags(tags: Map<String, TagValue>) = mutate { it.copy(tags = tags) }

  fun setOptedIn(optedIn: Boolean) = mutate { it.copy(optedIn = optedIn) }

  /**
   * What the operating system answers for itself, re-read rather than
   * remembered: a person who changes their phone's language has changed which
   * text they should be sent, and a launch is when we find out.
   */
  fun refreshDeviceAttributes(
    timezoneId: String?,
    locale: String?,
    appVersion: String?,
    appBuild: String?,
    bundleId: String?,
    osVersion: String?,
  ) {
    // Read here rather than passed in: the permission is one of the facts this
    // call exists to re-read, and the engine already holds the seam that answers
    // it. Read outside the monitor, because a binder call has no business under
    // a lock the registration loop also takes.
    val permission = currentPermission()

    mutate {
      it.copy(
        timezoneId = timezoneId,
        locale = locale,
        appVersion = appVersion,
        appBuild = appBuild,
        bundleId = bundleId,
        osVersion = osVersion,
        pushPermission = permission.wire,
      )
    }
  }

  /**
   * What the OS will do with a notification for this app, now.
   *
   * Nobody in the app calls a setter for this: it changes in Settings, while the
   * process is not running. The only thing that carries it to the server is the
   * next launch finding a body it has not sent before — the fingerprint is the
   * serialised body, so a flip is by construction a reason to register.
   */
  fun refreshPushPermission() {
    val permission = currentPermission()

    mutate { it.copy(pushPermission = permission.wire) }
  }

  private fun currentPermission(): PushPermission =
    if (permissions.notificationsEnabled()) PushPermission.ALLOWED else PushPermission.DENIED

  private fun mutate(change: (DeviceState) -> DeviceState) {
    synchronized(lock) {
      state = change(state)
      // Persisted on every change, not at send time. The process can be killed
      // between the two, and what survives has to be what the customer asked for
      // rather than what we last managed to deliver.
      store.state = state
    }

    startRegistrationLoop()
  }

  /**
   * Whether another call would tell the server anything it does not know.
   *
   * The single question the loop turns on, and deliberately derived from state
   * rather than tracked as a flag. A flag has to be set by every mutation and
   * cleared by exactly the right one; this cannot be out of step with the truth,
   * because it is computed from it. It is also what makes a token that arrives
   * before `configure` register the moment a key does: nothing was consumed while
   * sending was impossible.
   */
  private fun pendingRegistration(): Pair<DeviceState, String>? =
    synchronized(lock) {
      if (state.token == null || key.isEmpty()) return@synchronized null

      val fingerprint = state.fingerprint()

      if (fingerprint == store.registeredFingerprint || fingerprint == refusedFingerprint) {
        return@synchronized null
      }

      state to fingerprint
    }

  /**
   * One loop at a time; the latest state wins.
   *
   * The loop re-reads the state at the top of every attempt rather than capturing
   * it once, so a change made while a call is in flight is picked up by the next
   * pass instead of racing it. Two concurrent registrations for one device is the
   * failure this prevents: they upsert the same row, and the one that lands
   * second is whichever was slower — which is not the newer one.
   */
  fun startRegistrationLoop() {
    synchronized(lock) {
      if (registrationJob != null) return

      registrationJob = scope.launch { runRegistrationLoop() }
    }
  }

  private suspend fun runRegistrationLoop() {
    var failures = 0

    while (true) {
      // A cold start with an unchanged device costs no call at all, which is what
      // makes "call register() on every launch" the cheap instruction the
      // documentation says it is.
      val pending = pendingRegistration()

      if (pending == null) {
        finishRegistrationLoop()

        return
      }

      val (snapshot, fingerprint) = pending
      val key = synchronized(lock) { this.key }
      val debug = isDebugEnabled
      val registration = snapshot.registrationBody().toMutableMap()
      synchronized(lock) {
        store.deviceId?.let { registration["device_id"] = it }
        registration["installation_secret"] = store.installationSecret
      }
      val body = Json.encode(registration)

      Log.write(debug) { "registering device" }

      when (val verdict = Verdict.of(transport.send(HttpRequest("POST", DEVICES, body, key)))) {
        is Verdict.Accepted -> {
          failures = 0
          recordRegistration(fingerprint, verdict.body, debug)
        }
        is Verdict.Retry -> {
          failures += 1
          val wait = Backoff.delayMs(failures)
          Log.write(debug) {
            "registration deferred (${verdict.reason}); retrying in ${wait / 1000}s"
          }
          record("retrying in ${wait / 1000}s: ${verdict.reason}")

          // Nothing to put back: the state still differs from what the server has
          // confirmed, so the next pass finds the same work waiting.
          clock.sleep(wait)
        }
        is Verdict.Refused -> {
          // Refused for a reason time will not change: a malformed token, a key
          // that may not register a device. Retrying would be a loop the customer
          // pays for and never sees. The reason is kept verbatim, because the API
          // writes every message to say what to do next.
          val summary = verdict.problem?.summary ?: "HTTP ${verdict.status}"

          synchronized(lock) {
            lastRegistrationAtMs = clock.nowMs()
            lastRegistrationResult = summary
            refusedFingerprint = fingerprint
          }

          Log.write(debug) { "registration refused: $summary" }
          finishRegistrationLoop()

          return
        }
      }
    }
  }

  /**
   * Clears the loop and, in the same breath, restarts it if work arrived while it
   * was winding down.
   *
   * Both inside one monitor on purpose. Releasing in between would leave an
   * instant in which the job is null and there is work waiting, and a mutation
   * landing in that instant would find a loop apparently running and wait for the
   * next app start.
   */
  private fun finishRegistrationLoop() {
    synchronized(lock) {
      registrationJob = null

      if (state.token == null || key.isEmpty()) return

      val fingerprint = state.fingerprint()

      if (fingerprint == store.registeredFingerprint || fingerprint == refusedFingerprint) return

      registrationJob = scope.launch { runRegistrationLoop() }
    }
  }

  var onDeviceIdChanged: ((String) -> Unit)? = null
    get() = synchronized(lock) { field }
    set(value) { synchronized(lock) { field = value } }

  private fun recordRegistration(fingerprint: String, response: String, debug: Boolean) {
    // The server names the device it created. Kept for `debugInfo()`, and read
    // leniently: a registration that succeeded must not be undone by a response
    // shape.
    val id = (Json.parse(response) as? Map<*, *>)?.get("id") as? String

    val handler = synchronized(lock) {
      val changed = id != null && id != store.deviceId
      store.registeredFingerprint = fingerprint
      refusedFingerprint = null
      if (id != null) store.deviceId = id
      lastRegistrationAtMs = clock.nowMs()
      lastRegistrationResult = "registered"
      if (changed) onDeviceIdChanged else null
    }
    if (id != null) handler?.invoke(id)

    Log.write(debug) { "registered as ${id ?: "an unnamed device"}" }
  }

  private fun record(result: String) {
    synchronized(lock) {
      lastRegistrationAtMs = clock.nowMs()
      lastRegistrationResult = result
    }
  }

  // Opens --------------------------------------------------------------------

  /**
   * The delivery id is the proof, so an absent one is not an event.
   *
   * A notification that did not come from Carillon — another SDK's, or one the
   * app raised itself — carries no `carillon` entry, and reporting a tap on it
   * would be reporting an open against nothing. Silence is correct here, and it
   * is what lets the app forward every intent it receives without having to work
   * out which ones are ours.
   */
  fun didOpen(data: Map<String, String>): Boolean {
    val debug = isDebugEnabled
    val deliveryId = deliveryIdIn(data)

    if (deliveryId == null) {
      Log.write(debug) { "a notification was opened that carries no delivery id" }

      return false
    }

    val at = clock.nowMs()
    val notification = OpenedNotification(deliveryId, data, at)

    val handler =
      synchronized(lock) {
        store.events = store.events + QueuedEvent(QueuedEvent.OPENED, deliveryId, at)
        if (onOpened == null) heldOpens.add(notification)

        onOpened
      }

    Log.write(debug) { "opened $deliveryId" }
    handler?.invoke(notification)
    startEventLoop()

    return true
  }

  /**
   * A message the app received rather than one somebody tapped.
   *
   * Nothing is queued. `received` needs a Notification Service Extension on iOS
   * and is a later, additive decision for the protocol as a whole; reporting one
   * from Android alone would produce an event type the API refuses and a figure
   * that means one thing on one platform and nothing on the other. Displaying the
   * message is the app's business — channels, importance and appearance stay
   * untouched.
   */
  fun didReceive(data: Map<String, String>) {
    Log.write(isDebugEnabled) {
      "received ${deliveryIdIn(data) ?: "a notification that is not ours"}"
    }
  }

  /**
   * Where the delivery id is, on this platform.
   *
   * FCM types `data` as a map of strings, so the server serialises the reserved
   * object into one — the same key and the same inner shape as the object APNs
   * carries, so both SDKs look for one thing. On a tap of a system-displayed
   * notification the entries arrive as intent extras, unchanged, which is why
   * this reads a plain map and the caller decides where the map came from.
   */
  private fun deliveryIdIn(data: Map<String, String>): String? {
    val reserved = data[RESERVED_KEY] ?: return null

    return (Json.parse(reserved) as? Map<*, *>)?.get("delivery_id") as? String
  }

  fun currentOnOpened(): ((OpenedNotification) -> Unit)? = synchronized(lock) { onOpened }

  fun setOnOpened(handler: ((OpenedNotification) -> Unit)?) {
    val held =
      synchronized(lock) {
        onOpened = handler

        if (handler == null) {
          emptyList()
        } else {
          ArrayList(heldOpens).also { heldOpens.clear() }
        }
      }

    if (handler == null) return

    held.forEach(handler)
  }

  // Events -------------------------------------------------------------------

  /**
   * A hundred at a time, at least once, until the server takes them.
   *
   * The batch cap is the API's. At-least-once is the design rather than a
   * concession: the server is idempotent per delivery and event type, so a replay
   * after a connection dropped mid-request changes nothing — and that is what
   * makes it safe to keep an event until a 2xx has actually been seen, rather
   * than dropping it the moment it was handed to the network.
   */
  fun startEventLoop() {
    synchronized(lock) {
      if (eventJob != null) return

      eventJob = scope.launch { runEventLoop() }
    }
  }

  private suspend fun runEventLoop() {
    var failures = 0

    while (true) {
      val queued = synchronized(lock) { store.events }
      val key = synchronized(lock) { this.key }
      val debug = isDebugEnabled

      if (queued.isEmpty() || key.isEmpty()) break

      val batch = queued.take(MAX_EVENTS_PER_BATCH)
      val body = Json.encode(mapOf("events" to batch.map { it.json() }))

      when (val verdict = Verdict.of(transport.send(HttpRequest("POST", EVENTS, body, key)))) {
        is Verdict.Accepted -> {
          failures = 0
          drop(batch)
          Log.write(debug) { "reported ${batch.size} event(s)" }
        }
        is Verdict.Retry -> {
          failures += 1
          val wait = Backoff.delayMs(failures)
          Log.write(debug) { "events deferred (${verdict.reason}); retrying in ${wait / 1000}s" }
          clock.sleep(wait)
        }
        is Verdict.Refused -> {
          // The batch will be just as invalid in five minutes. Dropped so the
          // queue cannot grow for the life of the install, and logged so the
          // reason is visible rather than inferred from opens that never appear.
          drop(batch)
          Log.write(debug) {
            "events refused: ${verdict.problem?.summary ?: "HTTP ${verdict.status}"}"
          }
        }
      }
    }

    // Cleared and restarted inside one monitor, for the reason the registration
    // loop is: an event queued as this one ends must not wait for the next launch
    // to be reported.
    synchronized(lock) {
      eventJob = null

      if (store.events.isEmpty() || key.isEmpty()) return

      eventJob = scope.launch { runEventLoop() }
    }
  }

  /** Removes exactly what was sent, and nothing that arrived meanwhile. */
  private fun drop(batch: List<QueuedEvent>) {
    synchronized(lock) {
      val remaining = ArrayList(store.events)
      batch.forEach { event ->
        val index = remaining.indexOf(event)
        if (index >= 0) remaining.removeAt(index)
      }
      store.events = remaining
    }
  }

  // Reading ------------------------------------------------------------------

  fun debugInfo(): DebugInfo =
    synchronized(lock) {
      DebugInfo(
        sdkVersion = Carillon.SDK_VERSION,
        key = key,
        endpoint = endpoint,
        token = state.token,
        deviceId = store.deviceId,
        environment = state.environment,
        bundleId = state.bundleId,
        appBuild = state.appBuild,
        osVersion = state.osVersion,
        pushPermission = state.pushPermission,
        lastRegistrationAtMs = lastRegistrationAtMs,
        lastRegistrationResult = lastRegistrationResult,
        queuedEvents = store.events.size,
      )
    }

  val currentState: DeviceState
    get() = synchronized(lock) { state }

  val isDebugEnabled: Boolean
    get() = synchronized(lock) { debugEnabled }

  /**
   * Awaits whatever is in flight.
   *
   * The SDK never needs this; a test always does, and the alternative is
   * sprinkling waits and timeouts over assertions that are otherwise exact.
   */
  suspend fun settle() {
    while (true) {
      val jobs = synchronized(lock) { listOfNotNull(registrationJob, eventJob, tokenJob) }

      if (jobs.isEmpty()) return

      jobs.forEach { it.join() }
    }
  }

  internal companion object {
    /**
     * The batch cap the API enforces. Reaching it is the ordinary case for an app
     * that has been offline, not an anomaly.
     */
    const val MAX_EVENTS_PER_BATCH = 100
    const val RESERVED_KEY = "carillon"
    const val DEVICES = "/v1/devices"
    const val EVENTS = "/v1/events"
  }
}
