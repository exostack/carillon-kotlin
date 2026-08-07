# carillon-kotlin

Carillon Android SDK. Native Kotlin; the only dependency is Firebase
Messaging, the platform vendor's push channel.

`:carillon` is the published module; `:example` is the test-bench application
and is never published.

## Integrating

```kotlin
Carillon.configure(context, key = "carillon_mk_live_…", debug = true)
Carillon.register()   // suspending; DENIED when POST_NOTIFICATIONS is refused
```

Declare the service the SDK ships, or forward two calls from your own
`FirebaseMessagingService` — Firebase dispatches to one service per
application, so declaring both means one of them silently never runs:

```kotlin
override fun onNewToken(token: String) = Carillon.didRotate(token)
override fun onMessageReceived(message: RemoteMessage) = Carillon.didReceive(message)
```

And forward the launching intent, so a tap is reported:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  Carillon.didOpen(intent)
}

override fun onNewIntent(intent: Intent?) {
  super.onNewIntent(intent)
  Carillon.didOpen(intent)
}
```

## Tests

`./gradlew build` from the repository root. Every unit test runs on the JVM: the
seams that touch Android — preferences, permissions, debuggability, Firebase —
are interfaces with in-memory fakes, so nothing here needs an emulator or
Robolectric.

## Conformance fixtures

`carillon/src/test/resources/registration.json` and `events.json` are copied
**verbatim** from `carillon-swift`. They are the contract between every Carillon
SDK — a state or an event queue, and the exact request it must produce — and
`ConformanceTests` replays every case. Never edit them here: a change to a
vector is a cross-SDK decision that starts on the server side and lands in every
language at once.
