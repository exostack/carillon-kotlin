# carillon-kotlin

Carillon SDK for Android API 24 and later. Uses Firebase Messaging and Kotlin coroutines.

## Install

Add the SDK to your app dependencies:

```kotlin
implementation("dev.carillon:carillon:0.1.1")
```

Configure Firebase for your app package name, add `google-services.json`, and
apply the `com.google.gms.google-services` plugin. In Carillon, upload an FCM
service account for that Firebase project and copy a mobile key.

For a local SDK checkout, run `./gradlew publishToMavenLocal` and add
`mavenLocal()` to the consuming app repositories.

## Configure

Call once in `Application.onCreate`:

```kotlin
import dev.carillon.sdk.Carillon

Carillon.configure(this, key = "YOUR_MOBILE_KEY", debug = true)
```

This starts FCM token acquisition and device registration without a permission
prompt. Registration completes after a token is available and the API is reachable.
For staging or local development, pass an API base URL as `endpoint`.
Debug logging requires a debuggable app.

## Forward callbacks

Declare the SDK service inside `<application>` in your manifest:

```xml
<service android:name="dev.carillon.sdk.CarillonMessagingService"
         android:exported="false">
  <intent-filter>
    <action android:name="com.google.firebase.MESSAGING_EVENT" />
  </intent-filter>
</service>
```

If you already have a `FirebaseMessagingService`, forward these callbacks from
it instead. Declare only one messaging service:

```kotlin
override fun onNewToken(token: String) = Carillon.didRotate(token)
override fun onMessageReceived(message: RemoteMessage) = Carillon.didReceive(message)
```

`didReceive` does not display notifications or report received events.
Forward launcher intents to report opens:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  Carillon.didOpen(intent)
}

override fun onNewIntent(intent: Intent) {
  super.onNewIntent(intent)
  Carillon.didOpen(intent)
}
```

## Request permission

Declare notification permission in the manifest:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

From a coroutine with access to your activity:

```kotlin
val permission = Carillon.requestPermission(activity)
```

Returns `ALLOWED` or `DENIED` and syncs it to Carillon. Below API 33, reads
notification settings without showing a prompt.

## Update the device

```kotlin
import dev.carillon.sdk.tagOf

Carillon.identify("user-42")
Carillon.setTags(mapOf("plan" to tagOf("pro"), "seats" to tagOf(12)))
Carillon.clearIdentity()
Carillon.optOut()
Carillon.optIn()
```

Tags replace the entire map. Clearing identity keeps the device registered.
Opt-in changes sync to the server and do not change OS permission.

## Handle opens

```kotlin
Carillon.onOpened = { notification ->
  println(notification.deliveryId)
  println(notification.data)
}
```

Opens received before the handler is set are replayed when it attaches.

## Verify registration

```kotlin
println(Carillon.debugInfo())
```

Check `device_id` and `last_registration_result`. If registration has not
completed, check the token, endpoint, and Firebase configuration. Send a test
notification from the dashboard, tap it, and check that `onOpened` runs.
Diagnostics include the mobile key and token and are available in release builds.

## Develop

`:carillon` contains the library; `:example` contains a separate test app.
Run JVM unit tests without an emulator:

```sh
./gradlew :carillon:testDebugUnitTest
```

Build the example on a device or emulator to verify Firebase and permission callbacks.

`carillon/src/test/resources/registration.json` and `events.json` are copied
verbatim from `carillon-swift/Tests/ConformanceFixtures/`. Update them only as
part of a shared protocol change; do not edit individual fixtures in this repository.


## Device identity

```kotlin
val id = Carillon.deviceId
Carillon.onDeviceIdChanged = { id -> println(id) }
```

The SDK persists a random installation secret and the last confirmed device ID.
Token rotation reuses that ID when the server validates the proof. Reinstallation
or merging with an existing token registration can change the ID; the callback
fires on first registration and when the confirmed ID changes. The ID itself is
not a credential. Never log or export the installation secret.
