# carillon-kotlin

Carillon SDK for Android API 24 and later. Uses Firebase Messaging and Kotlin coroutines.

## Install

Add the SDK to your app dependencies:

```kotlin
implementation("com.exostack:carillon:0.3.0")
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

`didReceive` runs the foreground display path described under
[Foreground notifications](#foreground-notifications); it never reports a
received event. Forward launcher intents to report opens:

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

To read the current state without prompting, or to decide what to offer:

```kotlin
val current = Carillon.getPermission()

if (Carillon.canRequestPermission(activity)) {
  Carillon.requestPermission(activity)
} else {
  Carillon.openNotificationSettings(context)
}
```

`getPermission()` reads whether the system would display a notification for the
app, syncs it to Carillon and never prompts. `canRequestPermission(activity)`
is true on API 33 and later when the permission is not granted and either the
SDK has never asked or the system reports that a rationale should be shown; it
is false below API 33 and false once the system has stopped showing the
dialogue. `openNotificationSettings(context)` opens the app's notification
settings screen, falling back to the app details screen.

## Update the device

```kotlin
import dev.carillon.sdk.tagOf

Carillon.identify("user-42")
Carillon.setTags(mapOf("plan" to tagOf("pro"), "seats" to tagOf(12)))
Carillon.setTag("language", tagOf("fr"))
Carillon.setTags(mapOf("seats" to null))
Carillon.removeTag("language")
Carillon.clearIdentity()
Carillon.optOut()
Carillon.optIn()
```

Tags merge with existing keys, including tags written by your backend. Use `setTag` to update one key and `removeTag` to delete one. In `setTags`, a null value (`nil` in Swift) removes that key; omitted keys are preserved. Clearing identity keeps the device registered.
Opt-in changes sync to the server and do not change OS permission.

## Handle opens

```kotlin
Carillon.onOpened = { notification ->
  println(notification.deliveryId)
  println(notification.data)
}
```

Opens received before the handler is set are replayed when it attaches.

`Carillon.didOpen(intent)` keeps the customer data and the `carillon` stamp and
drops FCM's own transport extras (`google.*`, `gcm.*`, `from`, `collapse_key`,
`message_type`). A payload received through another library can be forwarded as
a map with the same result:

```kotlin
Carillon.didOpen(data)      // Map<String, String>; returns true when a delivery id was found
Carillon.didReceive(data)   // Map<String, String>; runs the receive path without a notification block
```

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
fires on first registration and when the confirmed ID changes, and a handler
attached after registration completed is called once with the known ID. The ID
itself is not a credential. Never log or export the installation secret.

### Backups

The installation secret is stored encrypted under an Android Keystore key that
never leaves the device. The SDK's `SharedPreferences` are therefore safe to
include in Auto Backup: no `android:allowBackup` or `dataExtractionRules`
exclusion is needed. On a device restored from another device's backup the
secret cannot be decrypted, so the SDK generates a new one, drops the restored
device ID and token, and registers as a new device. An installation that
upgrades from an earlier SDK version has its plaintext secret encrypted once on
first launch.

## Foreground notifications

`CarillonMessagingService` forwards incoming messages to `Carillon.didReceive(message)`.
If you supply your own Firebase service, forward that call yourself. Configure Carillon in
your `Application` so it is ready before callbacks arrive.

```kotlin
Carillon.onReceived = { notification ->
  // Return SUPPRESS when your app displays its own interface.
  NotificationPresentation.SHOW
}
```

When Firebase forwards a foreground Carillon notification, the SDK posts its title and body,
with `BigPictureStyle` when the image download succeeds. Image work runs through WorkManager
with a 10-second download budget and a 10 MiB cap; failures keep the text. The SDK preserves
the stamp and data in the tap intent. Continue forwarding launcher `onCreate` and `onNewIntent`
to `Carillon.didOpen(intent)`.

Display work is enqueued only for a message carrying a Carillon stamp or an FCM `notification`
block. A data-only message from another sender is ignored. A message that is displayable but
not Carillon's still reaches `onReceived`, with `deliveryId == null`, so an app with two
senders has one path; the SDK posts nothing for it. A Carillon message without a title or
body reaches `onReceived` and posts nothing.

A requested existing channel is used; otherwise the SDK creates `carillon_default` named
“Notifications”. Provide a `carillon_notification_icon` drawable for the small icon; the app
icon is the fallback. Android 8+ channel settings control sound. On Android 13+, display requires
notification permission. FCM displays background notification messages itself.

`Carillon.clearNotifications()` clears the app's notifications and cancels pending image/display
work. A download started before clearing cannot repost a notification afterwards.
