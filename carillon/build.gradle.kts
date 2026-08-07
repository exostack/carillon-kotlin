plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "dev.carillon.sdk"
  compileSdk = 36

  defaultConfig {
    minSdk = 24
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions {
    unitTests {
      // `android.util.Log` is a stub in the unit-test classpath and throws on
      // every call. Returning a default makes the debug-logging path runnable
      // off a device, which is the only way the flag that gates it is ever
      // exercised. Nothing else in this module reads an Android stub: the
      // seams that would are interfaces with in-memory fakes, precisely so
      // that no test needs Robolectric — a third party, in an SDK with one
      // dependency.
      isReturnDefaultValues = true
    }
  }
}

dependencies {
  // The one permitted platform dependency: the vendor's own push channel,
  // as UserNotifications is Apple's on iOS. Nothing else enters.
  //
  // `api` for the same reason coroutines is: `Carillon.didReceive(RemoteMessage)`
  // puts a Firebase type in the public surface, and every app that keeps its
  // own FirebaseMessagingService and forwards the two calls compiles against
  // it. A dependency the public surface requires is part of that surface.
  api(libs.firebase.messaging)
  // The language's own concurrency vocabulary. See the note in the catalogue.
  // `api` rather than `implementation`: `register()` is a public suspend
  // function, so a customer calling it needs a scope to call it from, and a
  // dependency the public surface requires is part of that surface.
  api(libs.coroutines.android)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.coroutines.test)
}
